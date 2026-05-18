package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.retrieval.api.RagResponseNoticeView;
import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import com.involutionhell.backend.rag.retrieval.observability.RagRetrievalMetrics;
import com.involutionhell.backend.rag.retrieval.support.RagRequestFeedbacks;
import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 启用 Milvus 后，组合向量检索和关键词检索，再用 RRF 做融合排序。
 */
@Service
@Primary
@ConditionalOnBean(MilvusVectorStore.class)
@ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "true")
public class HybridRagRetriever implements RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRagRetriever.class);

    private final RagMilvusRetriever ragMilvusRetriever;
    private final KeywordRagRetriever keywordRagRetriever;
    private final RagDocumentJoiner ragDocumentJoiner;
    private final RagProperties ragProperties;
    private final Executor ragVirtualThreadExecutor;
    private final RagRetrievalMetrics retrievalMetrics;

    @Autowired
    public HybridRagRetriever(
            RagMilvusRetriever ragMilvusRetriever,
            KeywordRagRetriever keywordRagRetriever,
            RagDocumentJoiner ragDocumentJoiner,
            RagProperties ragProperties,
            @Qualifier("ragVirtualThreadExecutor") Executor ragVirtualThreadExecutor,
            RagRetrievalMetrics retrievalMetrics) {
        this.ragMilvusRetriever = ragMilvusRetriever;
        this.keywordRagRetriever = keywordRagRetriever;
        this.ragDocumentJoiner = ragDocumentJoiner;
        this.ragProperties = ragProperties;
        this.ragVirtualThreadExecutor = ragVirtualThreadExecutor;
        this.retrievalMetrics = retrievalMetrics;
    }

    /**
     * 向后兼容旧测试构造器。
     */
    @Deprecated
    public HybridRagRetriever(
            RagMilvusRetriever ragMilvusRetriever,
            KeywordRagRetriever keywordRagRetriever,
            RagDocumentJoiner ragDocumentJoiner,
            Executor ignoredExecutor,
            RagProperties ragProperties,
            Object ignoredMetrics
    ) {
        this.ragMilvusRetriever = ragMilvusRetriever;
        this.keywordRagRetriever = keywordRagRetriever;
        this.ragDocumentJoiner = ragDocumentJoiner;
        this.ragProperties = ragProperties;
        this.ragVirtualThreadExecutor = ignoredExecutor;
        this.retrievalMetrics = ignoredMetrics instanceof RagRetrievalMetrics metrics ? metrics : null;
    }

    @Override
    public List<RagRetrievedChunk> search(RagRetrievalRequest request) {
        String question = request.query();
        RagSearchFilter filter = request.filter();
        RagRetrievalBudget budget = request.budget();
        Set<RagResponseNoticeView> feedbacks = request.feedbacks();
        // semantic / keyword 互为降级来源，任何单分支慢或失败都不应阻塞另一分支产出结果。
        CompletableFuture<BranchResult> semanticFuture = CompletableFuture.supplyAsync(
                () -> searchBranch("semantic", feedbacks, () -> ragMilvusRetriever.search(request)),
                ragVirtualThreadExecutor
        );
        CompletableFuture<BranchResult> keywordFuture = CompletableFuture.supplyAsync(
                () -> searchBranch("keyword", feedbacks, () -> keywordRagRetriever.search(request)),
                ragVirtualThreadExecutor
        );
        BranchResult semantic = semanticFuture.join();
        BranchResult keyword = keywordFuture.join();
        if (semantic.failed() && keyword.failed()) {
            recordFallback("hybrid", "all_branches_failed");
            RagRequestFeedbacks.record(feedbacks, "hybrid_retrieve", "all_branches_failed", "混合检索所有分支均失败，已返回空上下文。");
        }
        List<RagRetrievedChunk> semanticResults = semantic.results();
        List<RagRetrievedChunk> keywordResults = keyword.results();
        List<RagRetrievedChunk> joined = ragDocumentJoiner.join(List.of(semanticResults, keywordResults), budget.perQueryTopK());
        log.debug(
                "Hybrid retrieval completed: questionPreview={}, answerTopK={}, perQueryTopK={}, semanticCandidateTopK={}, keywordCandidateTopK={}, semanticCount={}, keywordCount={}, joinedCount={}, hasFilter={}",
                RagLogHelper.previewQuestion(question),
                budget.answerTopK(),
                budget.perQueryTopK(),
                budget.semanticCandidateTopK(),
                budget.keywordCandidateTopK(),
                semanticResults.size(),
                keywordResults.size(),
                joined.size(),
                filter != null && !filter.isEmpty());
        return joined;
    }

    private BranchResult searchBranch(String branch, Set<RagResponseNoticeView> feedbacks, BranchSearch search) {
        try {
            return new BranchResult(search.search(), false);
        } catch (RuntimeException exception) {
            // 分支异常在 hybrid 层收敛为 notice，避免一次 Milvus/JDBC 抖动放大成整次问答失败。
            recordFallback(branch, "error");
            RagRequestFeedbacks.record(
                    feedbacks,
                    "hybrid_retrieve",
                    branch + "_error",
                    ("semantic".equals(branch) ? "语义检索" : "关键词检索") + "分支失败，已跳过该分支。"
            );
            log.warn(
                    "Hybrid retrieval branch failed and will be skipped: branch={}, error={}",
                    branch,
                    RagLogHelper.errorSummary(unwrap(exception))
            );
            return new BranchResult(List.of(), true);
        }
    }

    private void recordFallback(String branch, String reason) {
        if (retrievalMetrics != null) {
            retrievalMetrics.recordFallback("retrieve", branch, reason);
        }
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    private interface BranchSearch {
        List<RagRetrievedChunk> search();
    }

    private record BranchResult(List<RagRetrievedChunk> results, boolean failed) {
    }
}
