package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.shared.metadata.RagChunkType;
import com.bytedance.ai.shared.support.RagLogHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 按 {@link RagChunkType} 分发到不同 evidence searcher 的 keyword 检索路由器。
 *
 * <h2>并发模型</h2>
 * <p>多个 evidence 通道（如 PRODUCT_RECOMMEND 触发的 PRODUCT_PROFILE + MARKETING、FAQ_QA 触发的
 * FAQ_QUERY + FAQ_ANSWER）会被并行调度到 {@code ragVirtualThreadExecutor}，总延迟从
 * {@code T_profile + T_marketing} 降到 {@code max(T_profile, T_marketing)}。
 *
 * <p>外层 keyword vs semantic 的并发由 {@code ProductSearchSpiAdapter} 负责，本类只关心
 * keyword 通路内部的多 evidence 并行。两层并行使用同一个虚拟线程执行器。
 *
 * <p>结果按 {@code evidenceTypes} 的迭代顺序累加到 {@link KeywordSearchResult}，
 * 保证日志和原始 hits 列表的顺序稳定（哪怕 future 完成顺序乱序）。
 *
 * <h2>路由规则</h2>
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
 * <p>同一 productId 在不同 evidence 通道命中时分别记录 —— 后续 fusion 层按 productId 聚合即可。
 */
@Component
public class KeywordRetrievalRouter {

    private static final Logger log = LoggerFactory.getLogger(KeywordRetrievalRouter.class);

    private final ProductProfileSearcher productProfileSearcher;
    private final MarketingSearcher marketingSearcher;
    private final FaqSearcher faqSearcher;
    private final ReviewSearcher reviewSearcher;
    private final Executor ragVirtualThreadExecutor;

    public KeywordRetrievalRouter(
            ProductProfileSearcher productProfileSearcher,
            MarketingSearcher marketingSearcher,
            FaqSearcher faqSearcher,
            ReviewSearcher reviewSearcher,
            @Qualifier("ragVirtualThreadExecutor") Executor ragVirtualThreadExecutor
    ) {
        this.productProfileSearcher = productProfileSearcher;
        this.marketingSearcher = marketingSearcher;
        this.faqSearcher = faqSearcher;
        this.reviewSearcher = reviewSearcher;
        this.ragVirtualThreadExecutor = ragVirtualThreadExecutor;
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

        // 1) 把每个 evidence type 的 search 任务并行提交到虚拟线程池。
        //    用 LinkedHashMap 保留 evidenceTypes 的迭代顺序，便于后续按顺序累加。
        Map<RagChunkType, CompletableFuture<List<ProductSearchHit>>> futures = new LinkedHashMap<>();
        for (RagChunkType type : evidenceTypes) {
            CompletableFuture<List<ProductSearchHit>> future = CompletableFuture.supplyAsync(
                    () -> dispatch(type, query, condition, effectiveTopK),
                    ragVirtualThreadExecutor
            );
            futures.put(type, future);
        }

        // 2) 按 evidenceTypes 顺序 join 每个 future，单路异常 isolate 不影响其它路。
        List<String> failedEvidenceTypes = new ArrayList<>();
        for (Map.Entry<RagChunkType, CompletableFuture<List<ProductSearchHit>>> entry : futures.entrySet()) {
            RagChunkType type = entry.getKey();
            try {
                List<ProductSearchHit> hits = entry.getValue().join();
                result.addAll(type, hits);
                log.debug(
                        "Keyword evidence dispatch done: type={}, queryPreview={}, topK={}, hitCount={}",
                        type,
                        RagLogHelper.previewQuestion(query),
                        effectiveTopK,
                        hits == null ? 0 : hits.size()
                );
            } catch (CompletionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                failedEvidenceTypes.add(type.name());
                log.warn("Keyword evidence searcher failed: type={}, queryPreview={}, error={}",
                        type, RagLogHelper.previewQuestion(query), RagLogHelper.errorSummary(cause));
            } catch (RuntimeException exception) {
                failedEvidenceTypes.add(type.name());
                log.warn("Keyword evidence searcher failed: type={}, queryPreview={}, error={}",
                        type, RagLogHelper.previewQuestion(query), RagLogHelper.errorSummary(exception));
            }
        }

        // 3) 汇总日志：raw vs distinct vs multi-source。
        int rawSize = result.hits().size();
        int distinctSize = result.distinctByProductId().size();
        int multiSource = result.multiSourceProductCount();
        log.info(
                "Keyword retrieval routed: queryPreview={}, topK={}, evidenceTypes={}, rawHits={}, distinctProducts={}, multiSourceProducts={}, byType={}, degraded={}, parallel=true",
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
