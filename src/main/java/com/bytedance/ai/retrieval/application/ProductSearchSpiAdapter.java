package com.bytedance.ai.retrieval.application;

import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.retrieval.model.RagRetrievedChunk;
import com.bytedance.ai.retrieval.service.HybridRagRetriever;
import com.bytedance.ai.retrieval.service.KeywordRagRetriever;
import com.bytedance.ai.retrieval.service.RagRetrievalBudget;
import com.bytedance.ai.retrieval.service.RagRetrievalRequest;
import com.bytedance.ai.retrieval.service.RagRetriever;
import com.bytedance.ai.retrieval.spi.ProductSearchHit;
import com.bytedance.ai.retrieval.spi.ProductSearchRequest;
import com.bytedance.ai.retrieval.spi.ProductSearchSpi;
import com.bytedance.ai.shared.properties.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ProductSearchSpi} 的内部适配器：把上层"商品检索"请求翻译为
 * {@link RagRetrievalRequest}，优先调既有 hybrid retriever，按 documentId 聚合 chunk 为 SPU。
 *
 * <p>不在结果中暴露 catalog 的 SPU 业务字段（price / stock 等），上层若要展示需要再次
 * 调 {@link CatalogQueryFacade#getSpu}；这是为了让 SPI 接口不依赖 catalog 数据形状，
 * 也避免无用的 N+1 查询拖慢检索路径。
 */
@Service
class ProductSearchSpiAdapter implements ProductSearchSpi {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchSpiAdapter.class);
    private static final String CATALOG_SPU_URI_PREFIX = "catalog://spu/";
    private static final int PRODUCT_CHUNK_RECALL_MULTIPLIER = 5;
    private static final int MIN_PRODUCT_CHUNK_RECALL_TOP_K = 10;
    private static final int MAX_PRODUCT_CHUNK_RECALL_TOP_K = 50;

    private final ObjectProvider<HybridRagRetriever> hybridRetrieverProvider;
    private final ObjectProvider<KeywordRagRetriever> keywordRetrieverProvider;
    private final CatalogQueryFacade catalogQueryFacade;
    private final RagProperties ragProperties;

    ProductSearchSpiAdapter(
            ObjectProvider<HybridRagRetriever> hybridRetrieverProvider,
            ObjectProvider<KeywordRagRetriever> keywordRetrieverProvider,
            CatalogQueryFacade catalogQueryFacade,
            RagProperties ragProperties
    ) {
        this.hybridRetrieverProvider = hybridRetrieverProvider;
        this.keywordRetrieverProvider = keywordRetrieverProvider;
        this.catalogQueryFacade = catalogQueryFacade;
        this.ragProperties = ragProperties;
    }

    @Override
    public List<ProductSearchHit> search(ProductSearchRequest request) {
        if (request == null || !StringUtils.hasText(request.query())) {
            throw new IllegalArgumentException("ProductSearchRequest.query 不能为空");
        }
        int topK = effectiveTopK(request.topK());
        int recallTopK = productChunkRecallTopK(topK);
        RagRetrievalBudget budget = RagRetrievalBudget.legacy(recallTopK);
        RagRetrievalRequest retrievalRequest = new RagRetrievalRequest(
                request.query(),
                request.filter(),
                budget
        );

        List<RagRetrievedChunk> chunks = selectRetriever().search(retrievalRequest);
        if (chunks.isEmpty()) {
            log.debug("product search returned empty: query='{}'", abbreviate(request.query()));
            return List.of();
        }

        List<String> includeChunkTypes = request.includeChunkTypes();
        List<ProductSearchHit> hits = aggregateChunksToSpu(chunks, includeChunkTypes);
        hits = applyRestrictToSpuRefs(hits, request.restrictToSpuRefs());
        if (hits.size() > topK) {
            hits = hits.subList(0, topK);
        }
        log.debug(
                "product search done: query='{}', topK={}, recallTopK={}, hits={}",
                abbreviate(request.query()),
                topK,
                recallTopK,
                hits.size()
        );
        return hits;
    }

    private List<ProductSearchHit> aggregateChunksToSpu(List<RagRetrievedChunk> chunks, List<String> includeChunkTypes) {
        // documentId → 最高分 chunk（同一 SPU 多个 chunk 命中只保留最佳片段）
        Map<Long, RagRetrievedChunk> bestByDocument = new LinkedHashMap<>();
        for (RagRetrievedChunk chunk : chunks) {
            if (!matchesIncludedChunkType(chunk, includeChunkTypes)) {
                continue;
            }
            RagRetrievedChunk existing = bestByDocument.get(chunk.documentId());
            if (existing == null || score(chunk) > score(existing)) {
                bestByDocument.put(chunk.documentId(), chunk);
            }
        }

        List<ProductSearchHit> hits = new ArrayList<>(bestByDocument.size());
        for (RagRetrievedChunk chunk : bestByDocument.values()) {
            String externalRef = extractExternalRef(chunk.sourceUri());
            Optional<CatalogSpuView> spuOpt = externalRef == null
                    ? Optional.empty()
                    : catalogQueryFacade.findSpuByExternalRef(externalRef);
            hits.add(new ProductSearchHit(
                    spuOpt.map(CatalogSpuView::id).orElse(null),
                    chunk.documentId(),
                    externalRef,
                    score(chunk),
                    null,             // chunk_type 在 RagRetrievedChunk 上未直接暴露；W2 可扩展 chunk 模型补回
                    chunk.content(),
                    Map.of()
            ));
        }
        return hits;
    }

    private List<ProductSearchHit> applyRestrictToSpuRefs(List<ProductSearchHit> hits, List<String> restrictToSpuRefs) {
        if (restrictToSpuRefs == null || restrictToSpuRefs.isEmpty()) {
            return hits;
        }
        java.util.Set<String> allowed = new java.util.HashSet<>(restrictToSpuRefs);
        return hits.stream()
                .filter(hit -> hit.externalRef() != null && allowed.contains(hit.externalRef()))
                .toList();
    }

    private boolean matchesIncludedChunkType(RagRetrievedChunk chunk, List<String> includeChunkTypes) {
        if (includeChunkTypes == null || includeChunkTypes.isEmpty()) {
            return true;
        }
        // RagRetrievedChunk 目前没暴露 chunkType，先按 blockType 兜底；W2 把 chunkType 上提到
        // RagRetrievedChunk 后这里改成 chunk.chunkType()。
        String blockType = chunk.blockType();
        return blockType != null && includeChunkTypes.stream().anyMatch(blockType::equalsIgnoreCase);
    }

    private double score(RagRetrievedChunk chunk) {
        return chunk.score() == null ? 0.0d : chunk.score();
    }

    private String extractExternalRef(String sourceUri) {
        if (!StringUtils.hasText(sourceUri)) {
            return null;
        }
        if (sourceUri.startsWith(CATALOG_SPU_URI_PREFIX)) {
            String tail = sourceUri.substring(CATALOG_SPU_URI_PREFIX.length()).trim();
            return tail.isEmpty() ? null : tail;
        }
        return null;
    }

    private int effectiveTopK(Integer topK) {
        if (topK != null && topK > 0) {
            return topK;
        }
        return ragProperties.defaultTopK();
    }

    private int productChunkRecallTopK(int productTopK) {
        int expanded = Math.max(MIN_PRODUCT_CHUNK_RECALL_TOP_K, productTopK * PRODUCT_CHUNK_RECALL_MULTIPLIER);
        return Math.min(expanded, MAX_PRODUCT_CHUNK_RECALL_TOP_K);
    }

    private RagRetriever selectRetriever() {
        HybridRagRetriever hybridRetriever = hybridRetrieverProvider.getIfAvailable();
        if (hybridRetriever != null) {
            return hybridRetriever;
        }
        KeywordRagRetriever keywordRetriever = keywordRetrieverProvider.getIfAvailable();
        if (keywordRetriever != null) {
            return keywordRetriever;
        }
        throw new IllegalStateException("ProductSearchSpi requires a RagRetriever, but none is available");
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 96 ? text : text.substring(0, 96) + "...";
    }
}
