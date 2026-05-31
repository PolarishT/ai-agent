package com.bytedance.ai.graph.product.retrieval;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link ProductSearchSpi} 的内部适配器：把上层"商品检索"请求翻译为
 * 商品专用 keyword / semantic 召回，再按 productId 聚合并融合排序。
 *
 * <p>降级原因（{@link ProductSearchResult#degradedNotes()}）规范：
 * <ul>
 *   <li>{@code keyword_unavailable} / {@code semantic_unavailable}：分支抛异常或返回 null；</li>
 *   <li>{@code keyword_timeout} / {@code semantic_timeout}：分支超时；</li>
 *   <li>分支返回空列表不算降级，{@code degradedNotes} 保持空。</li>
 * </ul>
 */
@Service
class ProductSearchSpiAdapter implements ProductSearchSpi {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchSpiAdapter.class);

    private final ProductKeywordRagRetriever productKeywordRetriever;
    private final ObjectProvider<ProductSemanticRagRetriever> productSemanticRetrieverProvider;
    private final ProductRrfFusion productRrfFusion;
    private final ProductBaseRanker productBaseRanker;
    private final RagProperties ragProperties;
    private final Executor ragVirtualThreadExecutor;

    ProductSearchSpiAdapter(
            ProductKeywordRagRetriever productKeywordRetriever,
            ObjectProvider<ProductSemanticRagRetriever> productSemanticRetrieverProvider,
            ProductRrfFusion productRrfFusion,
            ProductBaseRanker productBaseRanker,
            RagProperties ragProperties,
            @Qualifier("ragVirtualThreadExecutor") Executor ragVirtualThreadExecutor
    ) {
        this.productKeywordRetriever = productKeywordRetriever;
        this.productSemanticRetrieverProvider = productSemanticRetrieverProvider;
        this.productRrfFusion = productRrfFusion;
        this.productBaseRanker = productBaseRanker;
        this.ragProperties = ragProperties;
        this.ragVirtualThreadExecutor = ragVirtualThreadExecutor;
    }

    @Override
    public ProductSearchResult searchProduct(ProductSearchRequest request) {
        if (request == null || !StringUtils.hasText(request.query())) {
            throw new IllegalArgumentException("ProductSearchRequest.query 不能为空");
        }
        int topK = effectiveTopK(request.topK());
        ProductHardFilter hardFilter = request.hardFilter() == null
                ? ProductHardFilter.empty()
                : request.hardFilter();
        ProductQueryCondition condition = request.condition();
        ProductQueryIntent intent = request.intentOrDefault();
        String keywordQuery = request.query();
        String semanticQuery = StringUtils.hasText(request.semanticQuery())
                ? request.semanticQuery()
                : request.query();
        int keywordCandidateTopK = Math.max(topK, ragProperties.productQuery().keywordCandidateTopK());
        int semanticCandidateTopK = Math.max(topK, ragProperties.productQuery().semanticCandidateTopK());

        List<String> degradedNotes = new ArrayList<>();

        CompletableFuture<List<ProductSearchHit>> keywordFuture = CompletableFuture
                .supplyAsync(() -> productKeywordRetriever.search(keywordQuery, condition, intent, keywordCandidateTopK),
                        ragVirtualThreadExecutor);
        long keywordTimeout = ragProperties.productQuery().keywordTimeoutMillis();
        if (keywordTimeout > 0) {
            keywordFuture = keywordFuture.orTimeout(keywordTimeout, TimeUnit.MILLISECONDS);
        }

        ProductSemanticRagRetriever semanticRetriever = productSemanticRetrieverProvider.getIfAvailable();
        CompletableFuture<List<ProductSearchHit>> semanticFuture;
        if (semanticRetriever == null) {
            degradedNotes.add("semantic_unavailable");
            semanticFuture = CompletableFuture.completedFuture(List.of());
        } else {
            semanticFuture = CompletableFuture
                    .supplyAsync(() -> semanticRetriever.search(semanticQuery, condition, semanticCandidateTopK),
                            ragVirtualThreadExecutor);
            long semanticTimeout = ragProperties.productQuery().semanticTimeoutMillis();
            if (semanticTimeout > 0) {
                semanticFuture = semanticFuture.orTimeout(semanticTimeout, TimeUnit.MILLISECONDS);
            }
        }

        List<ProductSearchHit> keywordHits = joinBranch(keywordFuture, "keyword", degradedNotes);
        List<ProductSearchHit> semanticHits = joinBranch(semanticFuture, "semantic", degradedNotes);

        List<ProductSearchHit> fusedHits = productRrfFusion.fuse(List.of(keywordHits, semanticHits), topK);
        List<ProductSearchHit> rankedHits = productBaseRanker.rank(fusedHits, keywordHits, semanticHits, hardFilter, topK);

        logRetrievalOutcome(keywordQuery, keywordHits, semanticHits, fusedHits, rankedHits, degradedNotes);
        return new ProductSearchResult(keywordHits, semanticHits, fusedHits, rankedHits, degradedNotes);
    }

    private List<ProductSearchHit> joinBranch(
            CompletableFuture<List<ProductSearchHit>> future,
            String branch,
            List<String> degradedNotes
    ) {
        try {
            List<ProductSearchHit> hits = future.join();
            return hits == null ? List.of() : hits;
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            String reason = cause instanceof TimeoutException ? "timeout" : "unavailable";
            degradedNotes.add(branch + "_" + reason);
            log.warn("product hybrid retrieval degraded: branch={}, reason={}, error={}",
                    branch, branch + "_" + reason, RagLogHelper.errorSummary(cause));
            return List.of();
        } catch (RuntimeException exception) {
            degradedNotes.add(branch + "_unavailable");
            log.warn("product hybrid retrieval degraded: branch={}, reason={}, error={}",
                    branch, branch + "_unavailable", RagLogHelper.errorSummary(exception));
            return List.of();
        }
    }

    private void logRetrievalOutcome(
            String query,
            List<ProductSearchHit> keywordHits,
            List<ProductSearchHit> semanticHits,
            List<ProductSearchHit> fusedHits,
            List<ProductSearchHit> rankedHits,
            List<String> degradedNotes
    ) {
        boolean keywordDown = degradedNotes.contains("keyword_unavailable") || degradedNotes.contains("keyword_timeout");
        boolean semanticDown = degradedNotes.contains("semantic_unavailable") || degradedNotes.contains("semantic_timeout");
        if (keywordDown && semanticDown) {
            log.error("product hybrid retrieval unavailable: degradedNotes={}, queryPreview={}",
                    degradedNotes, RagLogHelper.previewQuestion(query));
            return;
        }
        int totalUnique = fusedHits.size();
        if (keywordHits.isEmpty() && semanticHits.isEmpty()) {
            log.info("product hybrid retrieval zero hit: keywordCount=0, semanticCount=0, degraded={}", degradedNotes);
            return;
        }
        log.info(
                "product hybrid retrieval fused: queryPreview={}, keywordCount={}, semanticCount={}, fusedCount={}, rankedCount={}, degraded={}",
                RagLogHelper.previewQuestion(query),
                keywordHits.size(),
                semanticHits.size(),
                totalUnique,
                rankedHits.size(),
                degradedNotes
        );
        log.info(
                "product hybrid retrieval hits preview: keywordHits={}, semanticHits={}, rankedHits={}",
                summarize(keywordHits),
                summarize(semanticHits),
                summarize(rankedHits)
        );
    }

    private String summarize(List<ProductSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "[]";
        }
        int max = 10;
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < hits.size() && i < max; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            ProductSearchHit hit = hits.get(i);
            builder.append("{pid=").append(hit.productId())
                    .append(" score=").append(String.format("%.3f", hit.score()))
                    .append(" type=").append(hit.chunkType());
            Object evidenceTypes = hit.metadata() == null ? null : hit.metadata().get("evidenceTypes");
            if (evidenceTypes != null) {
                builder.append(" sources=").append(evidenceTypes);
            }
            builder.append("}");
        }
        if (hits.size() > max) {
            builder.append(", ...and ").append(hits.size() - max).append(" more");
        }
        builder.append("]");
        return builder.toString();
    }

    private int effectiveTopK(Integer topK) {
        if (topK != null && topK > 0) {
            return topK;
        }
        return ragProperties.defaultTopK();
    }
}
