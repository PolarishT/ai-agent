package com.involutionhell.backend.rag.retrieval.application;

import com.involutionhell.backend.rag.indexing.api.IndexingChunkQueryFacade;
import com.involutionhell.backend.rag.indexing.api.RagChunkSearchView;
import com.involutionhell.backend.rag.retrieval.api.RagAskCompletedView;
import com.involutionhell.backend.rag.retrieval.api.RagConversationMessage;
import com.involutionhell.backend.rag.retrieval.api.RagAskRequest;
import com.involutionhell.backend.rag.retrieval.api.RagAskStreamEvent;
import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import com.involutionhell.backend.rag.retrieval.observability.RagRetrievalMetrics;
import com.involutionhell.backend.rag.retrieval.persistence.RagConversationMessageRecord;
import com.involutionhell.backend.rag.retrieval.persistence.RagConversationRecord;
import com.involutionhell.backend.rag.retrieval.service.RagAnswerGenerator;
import com.involutionhell.backend.rag.retrieval.service.RagDocumentJoiner;
import com.involutionhell.backend.rag.retrieval.service.RagQueryExpander;
import com.involutionhell.backend.rag.retrieval.service.RagQueryExpansionResult;
import com.involutionhell.backend.rag.retrieval.service.RagQueryTransformationResult;
import com.involutionhell.backend.rag.retrieval.service.RagQueryTransformer;
import com.involutionhell.backend.rag.retrieval.service.RagRetrievalBudget;
import com.involutionhell.backend.rag.retrieval.service.RagRetrievalBudgetPlanner;
import com.involutionhell.backend.rag.retrieval.service.RagRetrievalRequest;
import com.involutionhell.backend.rag.retrieval.service.RagRetriever;
import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagOpenAiTokenCounter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

class RagAskServiceBudgetTests {

    @Test
    void retrievesEachExpandedQueryWithPlannedBudget() {
        FixedExpander expander = new FixedExpander(List.of("q1", "q2", "q3"));
        RecordingPlanner planner = new RecordingPlanner(
                new RagRetrievalBudget(2, 3, 2, 6, 12, false, "initial"),
                new RagRetrievalBudget(2, 3, 4, 12, 24, false, "retry")
        );
        RecordingRetriever retriever = new RecordingRetriever(request -> List.of(chunk(request.query().hashCode() & 1000L)));
        RagAskService service = service(expander, planner, retriever);

        service.askStream(request(2)).collectList().block();

        assertThat(planner.initialRetrievalQueries).containsExactly("q1", "q2", "q3");
        assertThat(retriever.requests).hasSize(3);
        assertThat(retriever.requests)
                .allSatisfy(recorded -> assertThat(recorded.budget().perQueryTopK()).isEqualTo(2));
    }

    @Test
    void triggersProgressiveWideningWhenInitialContextsAreInsufficient() {
        FixedExpander expander = new FixedExpander(List.of("q1"));
        RecordingPlanner planner = new RecordingPlanner(
                new RagRetrievalBudget(3, 1, 1, 3, 8, true, "initial"),
                new RagRetrievalBudget(3, 1, 3, 9, 24, false, "retry")
        );
        RecordingRetriever retriever = new RecordingRetriever(request -> {
            if ("retry".equals(request.budget().reason())) {
                return List.of(chunk(2L), chunk(3L));
            }
            return List.of(chunk(1L));
        });
        RagAskService service = service(expander, planner, retriever);

        List<RagAskStreamEvent> events = service.askStream(request(3)).collectList().block();

        assertThat(planner.retryRequested).isTrue();
        assertThat(retriever.requests).extracting(request -> request.budget().reason())
                .containsExactly("initial", "retry");
        RagAskCompletedView completed = (RagAskCompletedView) events.stream()
                .filter(event -> "completed".equals(event.event()))
                .findFirst()
                .orElseThrow()
                .data();
        assertThat(completed.degraded()).isTrue();
        assertThat(completed.contextCount()).isEqualTo(3);
        assertThat(completed.notices()).anySatisfy(notice ->
                assertThat(notice.code()).isEqualTo("progressive_widening"));
    }

    @Test
    void skipsProgressiveWideningWhenInitialContextsAreEnough() {
        FixedExpander expander = new FixedExpander(List.of("q1"));
        RecordingPlanner planner = new RecordingPlanner(
                new RagRetrievalBudget(2, 1, 2, 6, 12, true, "initial"),
                new RagRetrievalBudget(2, 1, 4, 12, 24, false, "retry")
        );
        RecordingRetriever retriever = new RecordingRetriever(ignored -> List.of(chunk(1L), chunk(2L)));
        RagAskService service = service(expander, planner, retriever);

        service.askStream(request(2)).collectList().block();

        assertThat(planner.retryRequested).isFalse();
        assertThat(retriever.requests).hasSize(1);
    }

    @Test
    void includesAnswerFallbackNoticeWhenChatModelIsUnavailable() {
        FixedExpander expander = new FixedExpander(List.of("q1"));
        RecordingPlanner planner = new RecordingPlanner(
                new RagRetrievalBudget(1, 1, 1, 3, 8, false, "initial"),
                new RagRetrievalBudget(1, 1, 2, 6, 12, false, "retry")
        );
        RecordingRetriever retriever = new RecordingRetriever(ignored -> List.of(chunk(1L)));
        RagAskService service = service(expander, planner, retriever);

        List<RagAskStreamEvent> events = service.askStream(request(1)).collectList().block();

        RagAskCompletedView completed = (RagAskCompletedView) events.stream()
                .filter(event -> "completed".equals(event.event()))
                .findFirst()
                .orElseThrow()
                .data();
        assertThat(completed.degraded()).isTrue();
        assertThat(completed.notices()).anySatisfy(notice -> {
            assertThat(notice.stage()).isEqualTo("answer_generate");
            assertThat(notice.code()).isEqualTo("no_chat_model");
        });
    }

    @Test
    void persistsAnswerBeforeEmittingDeltaWhenFinalCompletionFails() {
        FixedExpander expander = new FixedExpander(List.of("q1"));
        RecordingPlanner planner = new RecordingPlanner(
                new RagRetrievalBudget(1, 1, 1, 3, 8, false, "initial"),
                new RagRetrievalBudget(1, 1, 2, 6, 12, false, "retry")
        );
        RecordingRetriever retriever = new RecordingRetriever(ignored -> List.of(chunk(1L)));
        StubConversationService conversationService = new StubConversationService(true);
        RagAskService service = service(expander, planner, retriever, conversationService);

        List<RagAskStreamEvent> events = service.askStream(request(1)).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(RagAskStreamEvent::event).containsSubsequence("answer_delta", "error");
        assertThat(conversationService.streamedAnswer).contains("content 1");
        assertThat(conversationService.failedAnswer).isEqualTo(conversationService.streamedAnswer);
    }

    @Test
    void stillEmitsErrorWhenFailureMarkingAlsoFails() {
        FixedExpander expander = new FixedExpander(List.of("q1"));
        RecordingPlanner planner = new RecordingPlanner(
                new RagRetrievalBudget(1, 1, 1, 3, 8, false, "initial"),
                new RagRetrievalBudget(1, 1, 2, 6, 12, false, "retry")
        );
        RecordingRetriever retriever = new RecordingRetriever(ignored -> List.of(chunk(1L)));
        RagAskService service = service(expander, planner, retriever, new StubConversationService(true, true));

        List<RagAskStreamEvent> events = service.askStream(request(1)).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(RagAskStreamEvent::event).contains("error");
    }

    private RagAskService service(
            FixedExpander expander,
            RecordingPlanner planner,
            RecordingRetriever retriever
    ) {
        return service(expander, planner, retriever, new StubConversationService(false));
    }

    private RagAskService service(
            FixedExpander expander,
            RecordingPlanner planner,
            RecordingRetriever retriever,
            StubConversationService conversationService
    ) {
        RagProperties properties = RagProperties.defaults();
        RagRetrievalMetrics metrics = new RagRetrievalMetrics(emptyProvider());
        return new RagAskService(
                new EmptyChunkQueryFacade(),
                retriever,
                new FixedTransformer(metrics, properties),
                expander,
                new RagDocumentJoiner(properties),
                planner,
                new RagAnswerGenerator(emptyProvider(), metrics, properties),
                properties,
                null,
                metrics,
                Schedulers.immediate(),
                conversationService
        );
    }

    private RagAskRequest request(int topK) {
        return new RagAskRequest("user-001", "conv-001", "question", topK, null, null, null, null);
    }

    private RagRetrievedChunk chunk(Long chunkId) {
        return new RagRetrievedChunk(
                chunkId,
                10L,
                "title",
                "markdown",
                "docs/test.md",
                chunkId.intValue(),
                1.0d,
                "content " + chunkId,
                List.of(),
                "paragraph",
                null
        );
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return null;
            }

            @Override
            public T getObject() {
                return null;
            }
        };
    }

    private static final class FixedTransformer extends RagQueryTransformer {

        private FixedTransformer(RagRetrievalMetrics metrics, RagProperties properties) {
            super(emptyProvider(), emptyProvider(), properties, new RagOpenAiTokenCounter(properties), metrics);
        }

        @Override
        public RagQueryTransformationResult transform(String question, List<RagConversationMessage> history) {
            return new RagQueryTransformationResult(question, question, false, false, 1);
        }
    }

    private static final class FixedExpander extends RagQueryExpander {

        private final List<String> queries;

        private FixedExpander(List<String> queries) {
            super(emptyProvider(), RagProperties.defaults(), new RagRetrievalMetrics(emptyProvider()));
            this.queries = queries;
        }

        @Override
        public RagQueryExpansionResult expand(String question) {
            return new RagQueryExpansionResult(question, queries, queries.size() > 1, false);
        }
    }

    private static final class RecordingPlanner implements RagRetrievalBudgetPlanner {

        private final RagRetrievalBudget initialBudget;
        private final RagRetrievalBudget retryBudget;
        private List<String> initialRetrievalQueries = List.of();
        private boolean retryRequested;

        private RecordingPlanner(RagRetrievalBudget initialBudget, RagRetrievalBudget retryBudget) {
            this.initialBudget = initialBudget;
            this.retryBudget = retryBudget;
        }

        @Override
        public RagRetrievalBudget plan(
                String originalQuestion,
                List<String> retrievalQueries,
                RagSearchFilter filter,
                int requestedTopK,
                boolean isRetry
        ) {
            if (isRetry) {
                retryRequested = true;
                return retryBudget;
            }
            initialRetrievalQueries = List.copyOf(retrievalQueries);
            return initialBudget;
        }
    }

    private static final class RecordingRetriever implements RagRetriever {

        private final SearchCallback callback;
        private final List<RagRetrievalRequest> requests = new ArrayList<>();

        private RecordingRetriever(SearchCallback callback) {
            this.callback = callback;
        }

        @Override
        public List<RagRetrievedChunk> search(RagRetrievalRequest request) {
            requests.add(request);
            return callback.search(request);
        }
    }

    @FunctionalInterface
    private interface SearchCallback {
        List<RagRetrievedChunk> search(RagRetrievalRequest request);
    }

    private static final class EmptyChunkQueryFacade implements IndexingChunkQueryFacade {

        @Override
        public List<RagChunkSearchView> findKeywordCandidates(Set<String> tokens, int limit) {
            return List.of();
        }

        @Override
        public List<RagChunkSearchView> findActiveChunksByDocumentIdAndRange(Long documentId, int startChunkIndex, int endChunkIndex) {
            return List.of();
        }

        @Override
        public List<RagChunkSearchView> findSearchableByVectorIds(List<String> vectorIds) {
            return List.of();
        }
    }

    private static final class StubConversationService extends RagConversationService {

        private final boolean failComplete;
        private final boolean failFailureMarking;
        private String streamedAnswer = "";
        private String failedAnswer = "";

        private StubConversationService(boolean failComplete) {
            this(failComplete, false);
        }

        private StubConversationService(boolean failComplete, boolean failFailureMarking) {
            super(null, null, null, null, null);
            this.failComplete = failComplete;
            this.failFailureMarking = failFailureMarking;
        }

        @Override
        public AskConversationState beginAsk(
                String runId,
                String correlationId,
                String userId,
                String conversationId,
                String question,
                String requestId,
                Integer topK,
                RagSearchFilter filter
        ) {
            return new AskConversationState(
                    new RagConversationRecord(1L, conversationId, userId, "title", "ACTIVE", 0, now(), now(), null),
                    new RagConversationMessageRecord(1L, "msg:user", 1L, "user", question, "SUCCEEDED", null, correlationId, 1, now()),
                    new RagConversationMessageRecord(2L, "msg:assistant", 1L, "assistant", "", "STREAMING", null, correlationId, 2, now()),
                    List.of(),
                    runId,
                    correlationId,
                    requestId,
                    "RUNNING",
                    null,
                    null,
                    false
            );
        }

        @Override
        public RagConversationMessageRecord completeAsk(
                AskConversationState state,
                String answer,
                String retrievalQuestion,
                List<String> retrievalQueries,
                List<com.involutionhell.backend.rag.retrieval.api.RagContextView> contexts,
                List<com.involutionhell.backend.rag.retrieval.api.RagResponseNoticeView> notices,
                boolean generatedByModel,
                boolean degraded,
                String correlationId
        ) {
            if (failComplete) {
                throw new IllegalStateException("complete failed");
            }
            return new RagConversationMessageRecord(2L, "msg:assistant", 1L, "assistant", answer, "SUCCEEDED", null, correlationId, 2, now());
        }

        @Override
        public RagConversationMessageRecord streamAssistantAnswer(AskConversationState state, String answer) {
            streamedAnswer = answer;
            return new RagConversationMessageRecord(2L, "msg:assistant", 1L, "assistant", answer, "STREAMING", null, state.correlationId(), 2, now());
        }

        @Override
        public void failAsk(AskConversationState state, Throwable exception, List<com.involutionhell.backend.rag.retrieval.api.RagResponseNoticeView> notices) {
            if (failFailureMarking) {
                throw new IllegalStateException("fail marking failed");
            }
            failedAnswer = streamedAnswer;
        }

        private static OffsetDateTime now() {
            return OffsetDateTime.parse("2026-05-11T00:00:00Z");
        }
    }
}
