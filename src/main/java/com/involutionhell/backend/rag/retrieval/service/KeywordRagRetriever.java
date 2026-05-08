package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.indexing.api.IndexingChunkQueryFacade;
import com.involutionhell.backend.rag.indexing.api.RagChunkSearchView;
import com.involutionhell.backend.rag.retrieval.observability.RagRetrievalMetrics;
import com.involutionhell.backend.rag.shared.metadata.RagChunkMetadataHelper;
import com.involutionhell.backend.rag.shared.metadata.RagChunkMetadataView;
import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.involutionhell.backend.rag.retrieval.support.RagRequestFeedbacks;
import com.involutionhell.backend.rag.retrieval.support.RagStageTimeoutException;

/**
 * 关键词检索器，既可作为单独回退，也可参与混合检索融合。
 */
@Service
public class KeywordRagRetriever implements RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(KeywordRagRetriever.class);

    private final IndexingChunkQueryFacade indexingChunkQueryFacade;
    private final RagRetrievalScorer retrievalScorer;
    private final RagChunkMetadataHelper metadataHelper;
    private final RagProperties ragProperties;
    private final RagRetrievalMetrics retrievalMetrics;
    private final Executor ragVirtualThreadExecutor;

    public KeywordRagRetriever(
            IndexingChunkQueryFacade indexingChunkQueryFacade,
            RagRetrievalScorer retrievalScorer,
            RagChunkMetadataHelper metadataHelper,
            RagProperties ragProperties,
            RagRetrievalMetrics retrievalMetrics,
            @Qualifier("ragVirtualThreadExecutor") Executor ragVirtualThreadExecutor
    ) {
        this.indexingChunkQueryFacade = indexingChunkQueryFacade;
        this.retrievalScorer = retrievalScorer;
        this.metadataHelper = metadataHelper;
        this.ragProperties = ragProperties;
        this.retrievalMetrics = retrievalMetrics;
        this.ragVirtualThreadExecutor = ragVirtualThreadExecutor;
    }

    @Override
    public List<RagRetrievedChunk> search(RagRetrievalRequest request) {
        String question = request.query();
        RagSearchFilter filter = request.filter();
        RagRetrievalBudget budget = request.budget();
        int topK = budget.perQueryTopK();
        int candidateTopK = Math.max(topK, budget.keywordCandidateTopK());
        boolean hasFilter = filter != null && !filter.isEmpty();
        retrievalMetrics.recordRequest("keyword");
        long stageStart = System.nanoTime();
        try {
            KeywordSearchOutcome outcome = executeSearch(question, topK, candidateTopK, filter);
            retrievalMetrics.recordHitCount("keyword", "pgsql", "raw", outcome.rawHitCount());
            retrievalMetrics.recordHitCount("keyword", "pgsql", "final", outcome.results().size());
            if (outcome.results().isEmpty()) {
                retrievalMetrics.recordZeroHit("keyword");
            }
            retrievalMetrics.recordStage(
                    "keyword_retrieve",
                    Duration.ofNanos(System.nanoTime() - stageStart),
                    hasFilter,
                    true
            );
            log.debug(
                    "Keyword retrieval completed: questionPreview={}, tokenCount={}, candidateTopK={}, resultCount={}, hasFilter={}",
                    RagLogHelper.previewQuestion(question),
                    outcome.tokenCount(),
                    outcome.candidateTopK(),
                    outcome.results().size(),
                    hasFilter
            );
            return outcome.results();
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                RagStageTimeoutException timeoutException = exception instanceof RagStageTimeoutException stageTimeoutException
                        ? stageTimeoutException
                        : new RagStageTimeoutException(
                        "keyword_retrieve",
                        ragProperties.retrieval().keywordTimeoutMillis(),
                        "关键词检索超时"
                );
                log.warn(
                        "Keyword retrieval timed out: timeoutMillis={}, topK={}, hasFilter={}, questionPreview={}",
                        timeoutException.timeoutMillis(),
                        topK,
                        hasFilter,
                        RagLogHelper.previewQuestion(question)
                );
                RagRequestFeedbacks.recordTimeout("keyword_retrieve", "关键词检索超时，已跳过该检索分支。");
                retrievalMetrics.recordFallback("retrieve", "keyword", "timeout");
                retrievalMetrics.recordStage(
                        "keyword_retrieve",
                        Duration.ofNanos(System.nanoTime() - stageStart),
                        hasFilter,
                        "timeout"
                );
                throw timeoutException;
            }
            retrievalMetrics.recordStage(
                    "keyword_retrieve",
                    Duration.ofNanos(System.nanoTime() - stageStart),
                    hasFilter,
                    false
            );
            retrievalMetrics.recordFallback("retrieve", "keyword", "error");
            throw exception;
        }
    }

    private KeywordSearchOutcome executeSearch(String question, int topK, int candidateTopK, RagSearchFilter filter) {
        java.util.concurrent.CompletableFuture<KeywordSearchOutcome> future = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> doSearch(question, topK, candidateTopK, filter), ragVirtualThreadExecutor);
        long timeoutMillis = ragProperties.retrieval().keywordTimeoutMillis();
        if (timeoutMillis > 0) {
            future = future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        try {
            return future.join();
        } catch (RuntimeException exception) {
            throw propagate(exception);
        }
    }

    private KeywordSearchOutcome doSearch(String question, int topK, int candidateTopK, RagSearchFilter filter) {
        Set<String> tokens = retrievalScorer.extractTokens(question);
        List<RagChunkSearchView> candidates = indexingChunkQueryFacade.findKeywordCandidates(tokens, candidateTopK);
        List<RagRetrievedChunk> results = candidates.stream()
                .map(row -> toScoredChunk(row, tokens, filter))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparing(RagRetrievedChunk::score).reversed()
                        .thenComparing(RagRetrievedChunk::documentId)
                        .thenComparing(RagRetrievedChunk::chunkIndex))
                .limit(topK)
                .toList();
        return new KeywordSearchOutcome(results, tokens.size(), candidateTopK, candidates.size());
    }

    private RagRetrievedChunk toScoredChunk(RagChunkSearchView row, Set<String> tokens, RagSearchFilter filter) {
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
        double score = retrievalScorer.keywordScore(row, metadataView, tokens);
        return new RagRetrievedChunk(
                row.chunkId(),
                row.documentId(),
                row.title(),
                row.sourceType(),
                row.sourceUri(),
                row.chunkIndex(),
                score,
                row.chunkText(),
                metadataView.headingPath(),
                metadataView.blockType(),
                metadataView.codeLanguage()
        );
    }

    private RuntimeException propagate(RuntimeException exception) {
        Throwable error = unwrap(exception);
        if (error instanceof TimeoutException) {
            return new RagStageTimeoutException(
                    "keyword_retrieve",
                    ragProperties.retrieval().keywordTimeoutMillis(),
                    "关键词检索超时"
            );
        }
        if (error instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("关键词检索执行失败", error);
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

    private record KeywordSearchOutcome(
            List<RagRetrievedChunk> results,
            int tokenCount,
            int candidateTopK,
            int rawHitCount
    ) {
    }
}
