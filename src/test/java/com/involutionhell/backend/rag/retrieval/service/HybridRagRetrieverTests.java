package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRagRetrieverTests {

    @Test
    void keepsKeywordResultsWhenSemanticBranchFails() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RagRetrievedChunk keywordChunk = chunk(1L, 0, 0.8d);
            HybridRagRetriever retriever = retriever(
                    new FixedMilvusRetriever(new IllegalStateException("milvus down")),
                    new FixedKeywordRetriever(List.of(keywordChunk)),
                    executor
            );

            List<RagRetrievedChunk> results = retriever.search("question", 3, null);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().chunkId()).isEqualTo(keywordChunk.chunkId());
        }
    }

    @Test
    void keepsSemanticResultsWhenKeywordBranchFails() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            RagRetrievedChunk semanticChunk = chunk(2L, 1, 0.9d);
            HybridRagRetriever retriever = retriever(
                    new FixedMilvusRetriever(List.of(semanticChunk)),
                    new FixedKeywordRetriever(new IllegalStateException("pgsql down")),
                    executor
            );

            List<RagRetrievedChunk> results = retriever.search("question", 3, null);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().chunkId()).isEqualTo(semanticChunk.chunkId());
        }
    }

    @Test
    void returnsEmptyResultsWhenAllBranchesFail() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            HybridRagRetriever retriever = retriever(
                    new FixedMilvusRetriever(new IllegalStateException("milvus down")),
                    new FixedKeywordRetriever(new IllegalStateException("pgsql down")),
                    executor
            );

            assertThat(retriever.search("question", 3, null)).isEmpty();
        }
    }

    @Test
    void passesBudgetCandidateLimitsToBranchesWithoutHybridMultiplier() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            FixedMilvusRetriever semantic = new FixedMilvusRetriever(List.of(chunk(1L, 0, 0.9d)));
            FixedKeywordRetriever keyword = new FixedKeywordRetriever(List.of(chunk(2L, 1, 0.8d)));
            HybridRagRetriever retriever = retriever(semantic, keyword, executor);
            RagRetrievalBudget budget = new RagRetrievalBudget(3, 2, 4, 7, 11, false, "test");

            retriever.search(new RagRetrievalRequest("question", null, budget));

            assertThat(semantic.lastRequest.budget().perQueryTopK()).isEqualTo(4);
            assertThat(semantic.lastRequest.budget().semanticCandidateTopK()).isEqualTo(7);
            assertThat(keyword.lastRequest.budget().keywordCandidateTopK()).isEqualTo(11);
        }
    }

    private HybridRagRetriever retriever(
            RagMilvusRetriever semantic,
            KeywordRagRetriever keyword,
            Executor executor
    ) {
        return new HybridRagRetriever(
                semantic,
                keyword,
                new RagDocumentJoiner(RagProperties.defaults()),
                executor,
                RagProperties.defaults(),
                null
        );
    }

    private RagRetrievedChunk chunk(Long chunkId, int chunkIndex, double score) {
        return new RagRetrievedChunk(
                chunkId,
                10L,
                "title",
                "markdown",
                "docs/test.md",
                chunkIndex,
                score,
                "content",
                List.of(),
                "paragraph",
                null
        );
    }

    private static final class FixedMilvusRetriever extends RagMilvusRetriever {

        private final List<RagRetrievedChunk> results;
        private final RuntimeException exception;
        private RagRetrievalRequest lastRequest;

        private FixedMilvusRetriever(List<RagRetrievedChunk> results) {
            super(null, null, RagProperties.defaults(), null, null, null, null, Runnable::run);
            this.results = results;
            this.exception = null;
        }

        private FixedMilvusRetriever(RuntimeException exception) {
            super(null, null, RagProperties.defaults(), null, null, null, null, Runnable::run);
            this.results = List.of();
            this.exception = exception;
        }

        @Override
        public List<RagRetrievedChunk> search(RagRetrievalRequest request) {
            this.lastRequest = request;
            if (exception != null) {
                throw exception;
            }
            return results;
        }
    }

    private static final class FixedKeywordRetriever extends KeywordRagRetriever {

        private final List<RagRetrievedChunk> results;
        private final RuntimeException exception;
        private RagRetrievalRequest lastRequest;

        private FixedKeywordRetriever(List<RagRetrievedChunk> results) {
            super(null, null, null, RagProperties.defaults(), null, Runnable::run);
            this.results = results;
            this.exception = null;
        }

        private FixedKeywordRetriever(RuntimeException exception) {
            super(null, null, null, RagProperties.defaults(), null, Runnable::run);
            this.results = List.of();
            this.exception = exception;
        }

        @Override
        public List<RagRetrievedChunk> search(RagRetrievalRequest request) {
            this.lastRequest = request;
            if (exception != null) {
                throw exception;
            }
            return results;
        }
    }
}
