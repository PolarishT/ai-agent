package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.shared.metadata.RagChunkType;
import com.bytedance.ai.shared.support.RagLogHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 按 {@link RagChunkType} 分发到不同 evidence searcher 的 keyword 检索路由器。
 *
 * <p>路由规则：
 * <ul>
 *   <li>{@link RagChunkType#PRODUCT_PROFILE} → {@link ProductProfileSearcher}（catalog_product + catalog_sku）；</li>
 *   <li>{@link RagChunkType#MARKETING} → {@link MarketingSearcher}（catalog_product_knowledge）；</li>
 *   <li>{@link RagChunkType#FAQ_QUERY} → {@link FaqSearcher#searchQuestion(String, ProductQueryCondition, int)}；</li>
 *   <li>{@link RagChunkType#FAQ_ANSWER} → {@link FaqSearcher#searchAnswer(String, ProductQueryCondition, int)}；</li>
 *   <li>{@link RagChunkType#REVIEW} → {@link ReviewSearcher}（catalog_product_review）。</li>
 * </ul>
 *
 * <p><strong>关键不变量：</strong>router 调度的所有 searcher 仅访问原始业务表，不查
 * {@code rag_chunks} / {@code rag_documents}。该限制由各 searcher 类自身保证。
 *
 * <p>同一 productId 在不同 evidence 通道命中时会被分别记录 —— 后续 fusion 层按 productId
 * 聚合即可。
 */
@Component
public class KeywordRetrievalRouter {

    private static final Logger log = LoggerFactory.getLogger(KeywordRetrievalRouter.class);

    private final ProductProfileSearcher productProfileSearcher;
    private final MarketingSearcher marketingSearcher;
    private final FaqSearcher faqSearcher;
    private final ReviewSearcher reviewSearcher;

    public KeywordRetrievalRouter(
            ProductProfileSearcher productProfileSearcher,
            MarketingSearcher marketingSearcher,
            FaqSearcher faqSearcher,
            ReviewSearcher reviewSearcher
    ) {
        this.productProfileSearcher = productProfileSearcher;
        this.marketingSearcher = marketingSearcher;
        this.faqSearcher = faqSearcher;
        this.reviewSearcher = reviewSearcher;
    }

    public KeywordSearchResult search(
            String query,
            ProductQueryCondition condition,
            Set<RagChunkType> evidenceTypes,
            int topK
    ) {
        KeywordSearchResult result = KeywordSearchResult.empty();
        if (!StringUtils.hasText(query) || evidenceTypes == null || evidenceTypes.isEmpty()) {
            log.debug("Keyword retrieval skipped: emptyQuery={}, evidenceTypes={}",
                    !StringUtils.hasText(query), evidenceTypes);
            return result;
        }
        int effectiveTopK = Math.max(1, topK);
        List<String> failedEvidenceTypes = new ArrayList<>();
        for (RagChunkType type : evidenceTypes) {
            try {
                List<ProductSearchHit> hits = dispatch(type, query, condition, effectiveTopK);
                result.addAll(type, hits);
                log.debug(
                        "Keyword evidence dispatch done: type={}, queryPreview={}, topK={}, hitCount={}",
                        type,
                        RagLogHelper.previewQuestion(query),
                        effectiveTopK,
                        hits.size()
                );
            } catch (RuntimeException exception) {
                // 单路 searcher 失败不影响其它路；router 不抛错，让上层从可观测性看到降级。
                failedEvidenceTypes.add(type.name());
                log.warn("Keyword evidence searcher failed: type={}, queryPreview={}, error={}",
                        type, RagLogHelper.previewQuestion(query), RagLogHelper.errorSummary(exception));
            }
        }
        // 同一 productId 可能在多个 evidence 通道命中（PROFILE + MARKETING）；
        // 计算一次 distinct view 供日志展示，调用方调用 result.distinctByProductId() 得到同一列表。
        int rawSize = result.hits().size();
        int distinctSize = result.distinctByProductId().size();
        int multiSource = result.multiSourceProductCount();
        log.info(
                "Keyword retrieval routed: queryPreview={}, topK={}, evidenceTypes={}, rawHits={}, distinctProducts={}, multiSourceProducts={}, byType={}, degraded={}",
                RagLogHelper.previewQuestion(query),
                effectiveTopK,
                evidenceTypes,
                rawSize,
                distinctSize,
                multiSource,
                result.countByType(),
                failedEvidenceTypes
        );
        return result;
    }

    private List<ProductSearchHit> dispatch(
            RagChunkType type,
            String query,
            ProductQueryCondition condition,
            int topK
    ) {
        return switch (type) {
            case PRODUCT_PROFILE -> productProfileSearcher.search(query, condition, topK);
            case MARKETING -> marketingSearcher.search(query, condition, topK);
            case FAQ_QUERY -> faqSearcher.searchQuestion(query, condition, topK);
            case FAQ_ANSWER -> faqSearcher.searchAnswer(query, condition, topK);
            case REVIEW -> reviewSearcher.search(query, condition, topK);
        };
    }
}
