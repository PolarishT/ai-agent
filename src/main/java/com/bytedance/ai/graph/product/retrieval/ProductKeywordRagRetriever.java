package com.bytedance.ai.graph.product.retrieval;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import com.bytedance.ai.graph.product.retrieval.scenario.IntentEvidenceTypeResolver;
import com.bytedance.ai.graph.product.retrieval.scenario.KeywordRetrievalRouter;
import com.bytedance.ai.graph.product.retrieval.scenario.KeywordSearchResult;
import com.bytedance.ai.shared.metadata.RagChunkType;
import com.bytedance.ai.shared.support.RagLogHelper;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Intent-aware 商品 keyword 召回门面：把 {@link ProductQueryIntent} 翻译为
 * {@link RagChunkType} 集合，再委托给 {@link KeywordRetrievalRouter} 调度到对应原始表 searcher。
 *
 * <p>所有底层 SQL 都打在 {@code catalog_product} / {@code catalog_sku} /
 * {@code catalog_product_knowledge} / {@code catalog_product_faq} /
 * {@code catalog_product_review} 五张原始业务表上，<strong>绝不</strong>查 {@code rag_chunks}
 * 或 {@code rag_documents}。库存 / 价格 / 上下架状态 / SKU 状态等动态业务事实只可能
 * 通过这层 SQL 命中。
 *
 * <p>类名保留是为了兼容现有 {@code ProductSearchSpiAdapter} 与上游测试 stub。
 */
@Component
public class ProductKeywordRagRetriever {

    private static final Logger log = LoggerFactory.getLogger(ProductKeywordRagRetriever.class);

    private final KeywordRetrievalRouter router;
    private final IntentEvidenceTypeResolver evidenceTypeResolver;

    public ProductKeywordRagRetriever(
            KeywordRetrievalRouter router,
            IntentEvidenceTypeResolver evidenceTypeResolver
    ) {
        this.router = router;
        this.evidenceTypeResolver = evidenceTypeResolver;
    }

    /**
     * 旧 3 参数签名：调用方未声明 intent 时按 {@link ProductQueryIntent#PRODUCT_SEARCH} 走 catalog 主表。
     */
    public List<ProductSearchHit> search(String query, ProductQueryCondition condition, int topK) {
        return search(query, condition, ProductQueryIntent.PRODUCT_SEARCH, topK);
    }

    /**
     * 按 {@link ProductQueryIntent} 分发到对应原始表的 evidence searcher，结果按 productId 去重后
     * 返回，准备进入下游 fusion / rank。
     *
     * <p>去重规则：同一 productId 在多个 evidence 通道（如 PROFILE + MARKETING）命中时，
     * 保留最高分一条，多通道来源记录在 {@code hit.metadata().evidenceTypes}，方便上层解释「为什么命中」。
     */
    public List<ProductSearchHit> search(
            String query,
            ProductQueryCondition condition,
            ProductQueryIntent intent,
            int topK
    ) {
        ProductQueryIntent resolvedIntent = intent == null ? ProductQueryIntent.PRODUCT_SEARCH : intent;
        Set<RagChunkType> evidenceTypes = evidenceTypeResolver.resolve(resolvedIntent);
        KeywordSearchResult result = router.search(query, condition, evidenceTypes, topK);
        List<ProductSearchHit> distinct = result.distinctByProductId();
        log.info(
                "Product keyword retrieval ready for fusion: intent={}, evidenceTypes={}, queryPreview={}, rawHits={}, distinctProducts={}, multiSourceProducts={}, byType={}",
                resolvedIntent,
                evidenceTypes,
                RagLogHelper.previewQuestion(query),
                result.hits().size(),
                distinct.size(),
                result.multiSourceProductCount(),
                result.countByType()
        );
        return distinct;
    }
}
