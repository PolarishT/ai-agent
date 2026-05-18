package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.indexing.api.IndexingChunkQueryFacade;
import com.involutionhell.backend.rag.indexing.api.RagChunkSearchView;
import com.involutionhell.backend.rag.retrieval.api.RagResponseNoticeView;
import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import com.involutionhell.backend.rag.retrieval.observability.RagRetrievalMetrics;
import com.involutionhell.backend.rag.retrieval.support.RagRequestFeedbacks;
import com.involutionhell.backend.rag.retrieval.support.RagStageTimeoutException;
import com.involutionhell.backend.rag.shared.metadata.RagChunkMetadataHelper;
import com.involutionhell.backend.rag.shared.metadata.RagChunkMetadataView;
import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusSearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 启用 Milvus 后，改用向量相似度检索。
 */
@Service
@ConditionalOnBean(MilvusVectorStore.class)
@ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "true")
public class RagMilvusRetriever implements RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagMilvusRetriever.class);

    private final MilvusVectorStore vectorStore;
    private final IndexingChunkQueryFacade indexingChunkQueryFacade;
    private final RagProperties ragProperties;
    private final RagRetrievalScorer retrievalScorer;
    private final RagChunkMetadataHelper metadataHelper;
    private final RagMilvusNativeExpressionBuilder nativeExpressionBuilder;
    private final RagRetrievalMetrics retrievalMetrics;
    private final Executor ragVirtualThreadExecutor;

    public RagMilvusRetriever(
            MilvusVectorStore vectorStore,
            IndexingChunkQueryFacade indexingChunkQueryFacade,
            RagProperties ragProperties,
            RagRetrievalScorer retrievalScorer,
            RagChunkMetadataHelper metadataHelper,
            RagMilvusNativeExpressionBuilder nativeExpressionBuilder,
            RagRetrievalMetrics retrievalMetrics,
            @Qualifier("ragVirtualThreadExecutor") Executor ragVirtualThreadExecutor
    ) {
        this.vectorStore = vectorStore;
        this.indexingChunkQueryFacade = indexingChunkQueryFacade;
        this.ragProperties = ragProperties;
        this.retrievalScorer = retrievalScorer;
        this.metadataHelper = metadataHelper;
        this.nativeExpressionBuilder = nativeExpressionBuilder;
        this.retrievalMetrics = retrievalMetrics;
        this.ragVirtualThreadExecutor = ragVirtualThreadExecutor;
    }

    @Override
    public List<RagRetrievedChunk> search(RagRetrievalRequest request) {
        String question = request.query();
        RagSearchFilter filter = request.filter();
        RagRetrievalBudget budget = request.budget();
        Set<RagResponseNoticeView> feedbacks = request.feedbacks();
        int topK = budget.perQueryTopK();
        int candidateTopK = Math.max(topK, budget.semanticCandidateTopK());
        boolean hasFilter = filter != null && !filter.isEmpty();
        retrievalMetrics.recordRequest("semantic");
        long stageStart = System.nanoTime();
        try {
            MilvusSearchOutcome outcome = executeSearch(question, topK, candidateTopK, filter);
            retrievalMetrics.recordHitCount("semantic", "milvus", "raw", outcome.rawHitCount());
            retrievalMetrics.recordHitCount("semantic", "milvus", "vector_id", outcome.vectorIdCount());
            retrievalMetrics.recordHitCount("semantic", "milvus", "final", outcome.results().size());
            if (outcome.results().isEmpty()) {
                retrievalMetrics.recordZeroHit("semantic");
            }
            retrievalMetrics.recordStage(
                    "semantic_retrieve",
                    Duration.ofNanos(System.nanoTime() - stageStart),
                    hasFilter,
                    true
            );
            log.debug(
                "Semantic retrieval completed: questionPreview={}, topK={}, candidateTopK={}, documentCount={}, hasFilter={}",
                RagLogHelper.previewQuestion(question),
                topK,
                candidateTopK,
                outcome.rawHitCount(),
                hasFilter
            );
            log.debug(
                    "Semantic retrieval reranked: vectorIdCount={}, scoreCount={}, searchableCount={}, finalCount={}",
                    outcome.vectorIdCount(),
                    outcome.scoreCount(),
                    outcome.searchableHitCount(),
                    outcome.results().size()
            );
            return outcome.results();
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                RagStageTimeoutException timeoutException = exception instanceof RagStageTimeoutException stageTimeoutException
                        ? stageTimeoutException
                        : new RagStageTimeoutException(
                        "semantic_retrieve",
                        ragProperties.retrieval().semanticTimeoutMillis(),
                        "语义检索超时"
                );
                log.warn(
                        "Semantic retrieval timed out: timeoutMillis={}, topK={}, hasFilter={}, questionPreview={}",
                        timeoutException.timeoutMillis(),
                        topK,
                        hasFilter,
                        RagLogHelper.previewQuestion(question)
                );
                RagRequestFeedbacks.recordTimeout(feedbacks, "semantic_retrieve", "语义检索超时，已跳过该检索分支。");
                retrievalMetrics.recordFallback("retrieve", "semantic", "timeout");
                retrievalMetrics.recordStage(
                        "semantic_retrieve",
                        Duration.ofNanos(System.nanoTime() - stageStart),
                        hasFilter,
                        "timeout"
                );
                throw timeoutException;
            }
            retrievalMetrics.recordStage(
                    "semantic_retrieve",
                    Duration.ofNanos(System.nanoTime() - stageStart),
                    hasFilter,
                    false
            );
            retrievalMetrics.recordFallback("retrieve", "semantic", "error");
            throw exception;
        }
    }

    private MilvusSearchOutcome executeSearch(String question, int topK, int candidateTopK, RagSearchFilter filter) {
        java.util.concurrent.CompletableFuture<MilvusSearchOutcome> future = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> doSearch(question, topK, candidateTopK, filter), ragVirtualThreadExecutor);
        long timeoutMillis = ragProperties.retrieval().semanticTimeoutMillis();
        if (timeoutMillis > 0) {
            future = future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        try {
            return future.join();
        } catch (RuntimeException exception) {
            throw propagate(exception);
        }
    }

    private MilvusSearchOutcome doSearch(String question, int topK, int candidateTopK, RagSearchFilter filter) {
        SearchRequest searchRequest = buildSearchRequest(question, candidateTopK, filter);
        List<Document> documents = vectorStore.similaritySearch(searchRequest);
        List<String> vectorIds = documents.stream()
                .map(document -> stringMetadata(document, "vectorId"))
                .filter(StringUtils::hasText)
                .toList();
        if (vectorIds.isEmpty()) {
            log.debug("Semantic retrieval returned no usable vectorId: questionPreview={}", RagLogHelper.previewQuestion(question));
            return new MilvusSearchOutcome(List.of(), documents.size(), 0, 0, 0);
        }

        Map<String, Double> scoreByVectorId = new LinkedHashMap<>();
        Map<String, Long> generationByVectorId = new LinkedHashMap<>();
        for (Document document : documents) {
            String vectorId = stringMetadata(document, "vectorId");
            if (StringUtils.hasText(vectorId)) {
                // Milvus 可能因为相似度检索或最终一致性窗口返回重复 ID，这里只保留首个分数和 generation。
                scoreByVectorId.putIfAbsent(vectorId, document.getScore() == null ? 1.0d : document.getScore());
                Long indexGeneration = longMetadata(document, "indexGeneration");
                if (indexGeneration != null) {
                    generationByVectorId.putIfAbsent(vectorId, indexGeneration);
                }
            }
        }

        Set<String> tokens = retrievalScorer.extractTokens(question);
        List<RagChunkSearchView> searchableChunks = indexingChunkQueryFacade.findSearchableByVectorIds(vectorIds);
        List<RagRetrievedChunk> results = searchableChunks.stream()
                .map(row -> toRetrievedChunk(row, scoreByVectorId, generationByVectorId, tokens, filter))
                .filter(Objects::nonNull)
                .filter(chunk -> chunk.score() > 0)
                .sorted(Comparator.comparing(RagRetrievedChunk::score).reversed()
                        .thenComparing(RagRetrievedChunk::documentId)
                        .thenComparing(RagRetrievedChunk::chunkIndex))
                .limit(topK)
                .toList();
        return new MilvusSearchOutcome(results, documents.size(), vectorIds.size(), scoreByVectorId.size(), searchableChunks.size());
    }

    private RagRetrievedChunk toRetrievedChunk(
            RagChunkSearchView row,
            Map<String, Double> scoreByVectorId,
            Map<String, Long> generationByVectorId,
            Set<String> tokens,
            RagSearchFilter filter
    ) {
        // 向量写入发生在 COMMIT_INDEX，极端情况下新向量可能已经写入，但 active generation 尚未切换。
        // 这里额外用 generation 对齐，避免把半提交状态的向量结果拼回当前上下文。
        Long vectorGeneration = generationByVectorId.get(row.vectorId());
        if (vectorGeneration != null && !vectorGeneration.equals(row.indexGeneration())) {
            log.debug(
                    "Semantic retrieval dropped generation-mismatched chunk: documentId={}, chunkId={}, vectorId={}, milvusGeneration={}, activeGeneration={}",
                    row.documentId(),
                    row.chunkId(),
                    row.vectorId(),
                    vectorGeneration,
                    row.indexGeneration()
            );
            return null;
        }
        RagChunkMetadataView metadataView = metadataHelper.parse(row.metadata().toString());
        if (!metadataHelper.matches(row.sourceUri(), metadataView, filter)) {
            return new RagRetrievedChunk(
                    row.chunkId(),
                    row.documentId(),
                    row.title(),
                    row.sourceType(),
                    row.sourceUri(),
                    row.chunkIndex(),
                    0d,
                    row.chunkText(),
                    metadataView.headingPath(),
                    metadataView.blockType(),
                    metadataView.codeLanguage()
            );
        }
        double semanticScore = scoreByVectorId.getOrDefault(row.vectorId(), 1.0d);
        double rerankedScore = retrievalScorer.semanticRerankScore(metadataView, tokens, semanticScore);
        return new RagRetrievedChunk(
                row.chunkId(),
                row.documentId(),
                row.title(),
                row.sourceType(),
                row.sourceUri(),
                row.chunkIndex(),
                rerankedScore,
                row.chunkText(),
                metadataView.headingPath(),
                metadataView.blockType(),
                metadataView.codeLanguage()
        );
    }

    private String stringMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Long longMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private SearchRequest buildSearchRequest(String question, int candidateTopK, RagSearchFilter filter) {
        String nativeExpression = nativeExpressionBuilder.build(filter);
        if (StringUtils.hasText(nativeExpression)) {
            log.debug("Semantic retrieval using native Milvus filter: expr={}", RagLogHelper.abbreviate(nativeExpression, 160));
            return MilvusSearchRequest.milvusBuilder()
                    .query(question)
                    .topK(candidateTopK)
                    .similarityThreshold(ragProperties.milvus().similarityThreshold())
                    .nativeExpression(nativeExpression)
                    .build();
        }
        return SearchRequest.builder()
                .query(question)
                .topK(candidateTopK)
                .similarityThreshold(ragProperties.milvus().similarityThreshold())
                .build();
    }

    private RuntimeException propagate(RuntimeException exception) {
        Throwable error = unwrap(exception);
        if (error instanceof TimeoutException) {
            return new RagStageTimeoutException(
                    "semantic_retrieve",
                    ragProperties.retrieval().semanticTimeoutMillis(),
                    "语义检索超时"
            );
        }
        if (error instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("语义检索执行失败", error);
    }

    private boolean isTimeout(Throwable error) {
        Throwable unwrapped = unwrap(error);
        return unwrapped instanceof TimeoutException || unwrapped instanceof RagStageTimeoutException;
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record MilvusSearchOutcome(
            List<RagRetrievedChunk> results,
            int rawHitCount,
            int vectorIdCount,
            int scoreCount,
            int searchableHitCount
    ) {
    }
}
