package com.involutionhell.backend.rag.retrieval.application;

import com.involutionhell.backend.rag.indexing.api.IndexingChunkQueryFacade;
import com.involutionhell.backend.rag.indexing.api.RagChunkSearchView;
import com.involutionhell.backend.rag.retrieval.api.*;
import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import com.involutionhell.backend.rag.retrieval.observability.RagRetrievalMetrics;
import com.involutionhell.backend.rag.retrieval.service.*;
import com.involutionhell.backend.rag.retrieval.support.RagRequestFeedbacks;
import com.involutionhell.backend.rag.shared.metadata.RagChunkMetadataHelper;
import com.involutionhell.backend.rag.shared.metadata.RagChunkMetadataView;
import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagLogFields;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
class RagAskService implements RagAskFacade {

    private static final Logger log = LoggerFactory.getLogger(RagAskService.class);

    private final IndexingChunkQueryFacade indexingChunkQueryFacade;
    private final RagRetriever ragRetriever;
    private final RagQueryTransformer ragQueryTransformer;
    private final RagQueryExpander ragQueryExpander;
    private final RagDocumentJoiner ragDocumentJoiner;
    private final RagRetrievalBudgetPlanner retrievalBudgetPlanner;
    private final RagAnswerGenerator answerGenerator;
    private final RagProperties ragProperties;
    private final RagChunkMetadataHelper metadataHelper;
    private final RagRetrievalMetrics retrievalMetrics;
    private final Scheduler ragBlockingScheduler;
    private final RagConversationService conversationService;

    RagAskService(
            IndexingChunkQueryFacade indexingChunkQueryFacade,
            RagRetriever ragRetriever,
            RagQueryTransformer ragQueryTransformer,
            RagQueryExpander ragQueryExpander,
            RagDocumentJoiner ragDocumentJoiner,
            RagRetrievalBudgetPlanner retrievalBudgetPlanner,
            RagAnswerGenerator answerGenerator,
            RagProperties ragProperties,
            RagChunkMetadataHelper metadataHelper,
            RagRetrievalMetrics retrievalMetrics,
            @Qualifier("ragBlockingScheduler") Scheduler ragBlockingScheduler,
            RagConversationService conversationService
    ) {
        this.indexingChunkQueryFacade = indexingChunkQueryFacade;
        this.ragRetriever = ragRetriever;
        this.ragQueryTransformer = ragQueryTransformer;
        this.ragQueryExpander = ragQueryExpander;
        this.ragDocumentJoiner = ragDocumentJoiner;
        this.retrievalBudgetPlanner = retrievalBudgetPlanner;
        this.answerGenerator = answerGenerator;
        this.ragProperties = ragProperties;
        this.metadataHelper = metadataHelper;
        this.retrievalMetrics = retrievalMetrics;
        this.ragBlockingScheduler = ragBlockingScheduler;
        this.conversationService = conversationService;
    }

    @Override
    public Flux<RagAskStreamEvent> askStream(RagAskRequest request) {
        // 未传 requestId 时每次订阅创建独立问答；传入 requestId 时由持久化层复用既有轮次。
        return Flux.defer(() -> {
            String requestedCorrelationId = "ask:" + UUID.randomUUID();
            String runId = "run:" + UUID.randomUUID();
            int topK = request.topK() == null ? ragProperties.defaultTopK() : request.topK();
            RagSearchFilter filter = RagSearchFilter.of(
                    request.sourceUriPrefix(),
                    request.tags(),
                    request.headingPathContains()
            );
            RagConversationService.AskConversationState conversationState = conversationService.beginAsk(
                    runId,
                    requestedCorrelationId,
                    request.userId(),
                    request.conversationId(),
                    request.question(),
                    request.requestId(),
                    topK,
                    filter
            );
            String correlationId = conversationState.correlationId();
            Set<RagResponseNoticeView> feedbacks = RagRequestFeedbacks.begin();
            AtomicInteger sequence = new AtomicInteger();
            AtomicBoolean generatedByModel = new AtomicBoolean(false);
            AtomicBoolean askFinalized = new AtomicBoolean(false);
            StringBuilder answer = new StringBuilder();
            AskInitialState initialState = initialize(
                    requestWithHistory(request, conversationState.history()),
                    correlationId,
                    topK,
                    filter,
                    feedbacks,
                    conversationState
            );
            RagAskStreamEvent started = event(
                    sequence,
                    "started",
                    correlationId,
                    new RagAskStartedView(correlationId, request.question(), initialState.topK())
            );
            if (conversationState.existing()) {
                return replayExistingAsk(sequence, correlationId, request.question(), initialState.topK(), conversationState)
                        .doFinally(ignored -> RagRequestFeedbacks.clear());
            }

            return Flux.concat(
                            Flux.just(started),
                            transform(initialState)
                                    .flatMapMany(transformedState -> Flux.concat(
                                            Flux.just(toTransformedEvent(sequence, transformedState)),
                                            expand(transformedState)
                                                    .flatMapMany(expandedState -> Flux.concat(
                                                            Flux.just(toExpandedEvent(sequence, expandedState)),
                                                            retrieveContexts(expandedState)
                                                                    .flatMapMany(contextState -> emitAnswerFlow(
                                                                            sequence,
                                                                            contextState,
                                                                            generatedByModel,
                                                                            answer,
                                                                            askFinalized
                                                                    ))
                                                    ))
                                    ))
                    )
                    .onErrorResume(exception -> {
                        RagAskStreamEvent errorEvent = toErrorEvent(sequence, correlationId, feedbacks, exception);
                        if (askFinalized.compareAndSet(false, true)) {
                            conversationService.failAsk(conversationState, exception, RagRequestFeedbacks.snapshot());
                        }
                        return Flux.just(errorEvent);
                    })
                    .doFinally(signalType -> {
                        if (signalType == SignalType.CANCEL && askFinalized.compareAndSet(false, true)) {
                            conversationService.failAsk(
                                    conversationState,
                                    new CancellationException("RAG ask stream cancelled by client"),
                                    RagRequestFeedbacks.snapshot()
                            );
                        }
                        RagRequestFeedbacks.clear();
                    });
        });
    }

    private AskInitialState initialize(
            RagAskRequest request,
            String correlationId,
            int topK,
            RagSearchFilter filter,
            Set<RagResponseNoticeView> feedbacks,
            RagConversationService.AskConversationState conversationState
    ) {
        boolean hasFilter = !filter.isEmpty();
        retrievalMetrics.recordRequest("ask");
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "rag.ask.started")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_STARTED)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, correlationId)
                .addKeyValue(RagLogFields.RAG_QUESTION_LENGTH, request.question() == null ? 0 : request.question().length())
                .addKeyValue(RagLogFields.RAG_QUESTION_PREVIEW, RagLogHelper.previewQuestion(request.question()))
                .addKeyValue(RagLogFields.RAG_TOP_K, topK)
                .addKeyValue(RagLogFields.RAG_HISTORY_TURNS, request.history() == null ? 0 : request.history().size())
                .addKeyValue(RagLogFields.RAG_HAS_FILTER, hasFilter)
                .log("RAG ask started");
        return new AskInitialState(request, correlationId, topK, filter, hasFilter, feedbacks, conversationState);
    }

    private RagAskRequest requestWithHistory(RagAskRequest request, List<RagConversationMessage> history) {
        return new RagAskRequest(
                request.userId(),
                request.conversationId(),
                request.question(),
                request.topK(),
                request.sourceUriPrefix(),
                request.tags(),
                request.headingPathContains(),
                history,
                request.requestId()
        );
    }

    private Flux<RagAskStreamEvent> replayExistingAsk(
            AtomicInteger sequence,
            String correlationId,
            String question,
            int topK,
            RagConversationService.AskConversationState conversationState
    ) {
        RagAskStreamEvent started = event(
                sequence,
                "started",
                correlationId,
                new RagAskStartedView(correlationId, question, topK)
        );
        if ("SUCCEEDED".equals(conversationState.runStatus())) {
            return Flux.concat(
                    Flux.just(started),
                    Flux.just(event(
                            sequence,
                            "answer_delta",
                            correlationId,
                            new RagAnswerDeltaView(conversationState.assistantMessage().content())
                    )),
                    Flux.just(event(
                            sequence,
                            "completed",
                            correlationId,
                            new RagAskCompletedView(false, false, List.of(), 0)
                    ))
            );
        }
        if ("RUNNING".equals(conversationState.runStatus())) {
            return Flux.just(
                    started,
                    event(
                            sequence,
                            "error",
                            correlationId,
                            new RagStreamErrorView("duplicate_running", "同一 requestId 的问答仍在运行中。", true)
                    )
            );
        }
        String errorCode = conversationState.errorCode() == null ? "duplicate_failed" : conversationState.errorCode();
        String errorMessage = conversationState.errorMessage() == null ? "同一 requestId 的问答已失败。" : conversationState.errorMessage();
        return Flux.just(
                started,
                event(
                        sequence,
                        "error",
                        correlationId,
                        new RagStreamErrorView(errorCode, errorMessage, true)
                )
        );
    }

    private Mono<TransformedState> transform(AskInitialState state) {
        // Spring AI transformer 当前是阻塞调用；放到专用 scheduler，避免占用 WebFlux 请求线程。
        Mono<RagQueryTransformationResult> transform = Mono.fromCallable(() -> retrievalMetrics.recordStage(
                        "query_transform",
                        state.hasFilter(),
                        () -> ragQueryTransformer.transform(state.request().question(), state.request().history())
                ))
                .subscribeOn(ragBlockingScheduler);
        long timeoutMillis = ragProperties.queryTransformation().timeoutMillis();
        if (timeoutMillis > 0) {
            transform = transform.timeout(Duration.ofMillis(timeoutMillis));
        }
        return transform
                .onErrorResume(TimeoutException.class, exception -> {
                    // Reactor timeout 可能切到非子虚拟线程，显式恢复反馈集合后才能把 notice 汇总回 completed 事件。
                    RagRequestFeedbacks.restore(state.feedbacks());
                    retrievalMetrics.recordFallback("query_transform", "transform", "timeout");
                    RagRequestFeedbacks.recordTimeout("query_transform", "查询改写超时，已使用原始问题继续检索。");
                    return Mono.just(new RagQueryTransformationResult(
                            state.request().question(),
                            state.request().question(),
                            false,
                            false,
                            state.request().history() == null ? 1 : state.request().history().size() + 1
                    ));
                })
                .map(result -> new TransformedState(state, result));
    }

    private Mono<ExpandedState> expand(TransformedState state) {
        // Query expansion 可能触发模型调用，同样隔离到阻塞 scheduler，并用配置控制阶段预算。
        Mono<RagQueryExpansionResult> expand = Mono.fromCallable(() -> retrievalMetrics.recordStage(
                        "query_expand",
                        state.initial().hasFilter(),
                        () -> {
                            RagQueryExpansionResult result = ragQueryExpander.expand(state.transformedQuery().retrievalQuestion());
                            log.atInfo()
                                    .addKeyValue(RagLogFields.RAG_QUERY_EXPANDED, result)
                                    .log("RAG ask expanded");
                            return result;
                        }
                ))
                .subscribeOn(ragBlockingScheduler);
        long timeoutMillis = ragProperties.queryExpansion().timeoutMillis();
        if (timeoutMillis > 0) {
            expand = expand.timeout(Duration.ofMillis(timeoutMillis));
        }
        return expand
                .onErrorResume(TimeoutException.class, exception -> {
                    RagRequestFeedbacks.restore(state.initial().feedbacks());
                    retrievalMetrics.recordFallback("query_expand", "multi_query", "timeout");
                    RagRequestFeedbacks.recordTimeout("query_expand", "查询扩展超时，已使用单查询继续检索。");
                    String retrievalQuestion = state.transformedQuery().retrievalQuestion();
                    return Mono.just(new RagQueryExpansionResult(retrievalQuestion, List.of(retrievalQuestion), false, false));
                })
                .map(result -> {
                    retrievalMetrics.recordExpandedQueryCount(result.retrievalQueries().size());
                    RagRetrievalBudget budget = retrievalBudgetPlanner.plan(
                            state.initial().request().question(),
                            result.retrievalQueries(),
                            state.initial().filter(),
                            state.initial().topK(),
                            false
                    );
                    logPreprocessed(state, result, budget);
                    return new ExpandedState(state, result, budget);
                });
    }

    private Mono<ContextState> retrieveContexts(ExpandedState state) {
        // 多 query 扇出是本链路主要延迟来源；并发度由配置限制，防止一次请求打满 JDBC/Milvus 资源。
        RagRetrievalBudget initialBudget = state.budget();
        return retrieveWithBudget(state, initialBudget)
                .flatMap(results -> join(state, results, initialBudget.answerTopK())
                        .flatMap(joined -> maybeProgressiveWiden(state, results, joined, initialBudget)))
                .flatMap(joined -> expandNeighborWindow(state, joined))
                .map(contexts -> {
                    if (contexts == null || contexts.isEmpty()) {
                        retrievalMetrics.recordZeroHit("ask");
                    }
                    return new ContextState(state, contexts == null ? List.of() : contexts);
                });
    }

    private Mono<List<List<RagRetrievedChunk>>> retrieveWithBudget(ExpandedState state, RagRetrievalBudget budget) {
        return Flux.fromIterable(state.expandedQuery().retrievalQueries())
                .flatMap(query -> retrieveOneQuery(query, state, budget), ragProperties.retrieval().queryConcurrency())
                .collectList();
    }

    private Mono<List<RagRetrievedChunk>> retrieveOneQuery(String query, ExpandedState state, RagRetrievalBudget budget) {
        // Retriever 内部仍是同步 API，外层用 Mono 包装以获得 query 级超时和分支级降级。
        Mono<List<RagRetrievedChunk>> retrieve = Mono.fromCallable(() -> ragRetriever.search(new RagRetrievalRequest(
                        query,
                        state.transformed().initial().filter(),
                        budget
                )))
                .subscribeOn(ragBlockingScheduler);
        long timeoutMillis = ragProperties.retrieval().queryTimeoutMillis();
        if (timeoutMillis > 0) {
            retrieve = retrieve.timeout(Duration.ofMillis(timeoutMillis));
        }
        return retrieve.onErrorResume(exception -> {
            RagRequestFeedbacks.restore(state.transformed().initial().feedbacks());
            retrievalMetrics.recordFallback("retrieve", "query", isTimeout(exception) ? "timeout" : "error");
            String message = isTimeout(exception)
                    ? "单路检索超时，已跳过该查询分支。"
                    : "单路检索失败，已跳过该查询分支。";
            RagRequestFeedbacks.record("retrieve", isTimeout(exception) ? "timeout" : "error", message);
            log.warn(
                    "RAG query branch failed and will be skipped: queryPreview={}, error={}",
                    RagLogHelper.previewQuestion(query),
                    RagLogHelper.errorSummary(exception)
            );
            return Mono.just(List.of());
        });
    }

    private Mono<List<RagRetrievedChunk>> join(ExpandedState state, List<List<RagRetrievedChunk>> retrievalResults, int topK) {
        return Mono.fromCallable(() -> retrievalMetrics.recordStage(
                        "join",
                        state.transformed().initial().hasFilter(),
                        () -> ragDocumentJoiner.join(retrievalResults, topK)
                ))
                .subscribeOn(ragBlockingScheduler);
    }

    private Mono<List<RagRetrievedChunk>> maybeProgressiveWiden(
            ExpandedState state,
            List<List<RagRetrievedChunk>> initialResults,
            List<RagRetrievedChunk> joined,
            RagRetrievalBudget initialBudget
    ) {
        if (!initialBudget.progressiveEnabled() || joined.size() >= initialBudget.answerTopK()) {
            return Mono.just(joined);
        }
        RagRequestFeedbacks.restore(state.transformed().initial().feedbacks());
        RagRequestFeedbacks.record(
                "retrieve",
                "progressive_widening",
                "初次召回上下文不足，已触发二次候选集放大。"
        );
        retrievalMetrics.recordFallback("retrieve", "progressive", "insufficient_contexts");
        RagRetrievalBudget retryBudget = retrievalBudgetPlanner.plan(
                state.transformed().initial().request().question(),
                state.expandedQuery().retrievalQueries(),
                state.transformed().initial().filter(),
                state.transformed().initial().topK(),
                true
        );
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "rag.ask.progressive_widening")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_STARTED)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.transformed().initial().correlationId())
                .addKeyValue(RagLogFields.RAG_CONTEXT_COUNT, joined.size())
                .addKeyValue(RagLogFields.RAG_TOP_K, initialBudget.answerTopK())
                .addKeyValue("rag.retry_per_query_top_k", retryBudget.perQueryTopK())
                .addKeyValue("rag.retry_semantic_candidate_top_k", retryBudget.semanticCandidateTopK())
                .addKeyValue("rag.retry_keyword_candidate_top_k", retryBudget.keywordCandidateTopK())
                .addKeyValue("rag.retrieval_budget_reason", retryBudget.reason())
                .log("RAG progressive widening triggered");
        return retrieveWithBudget(state, retryBudget)
                .flatMap(retryResults -> {
                    List<List<RagRetrievedChunk>> combined = new ArrayList<>(initialResults.size() + retryResults.size());
                    combined.addAll(initialResults);
                    combined.addAll(retryResults);
                    return join(state, combined, retryBudget.answerTopK());
                });
    }

    private Mono<List<RagRetrievedChunk>> expandNeighborWindow(ExpandedState state, List<RagRetrievedChunk> joinedContexts) {
        return Mono.fromCallable(() -> retrievalMetrics.recordStage(
                        "neighbor_window",
                        state.transformed().initial().hasFilter(),
                        () -> expandNeighborWindow(joinedContexts)
                ))
                .subscribeOn(ragBlockingScheduler);
    }

    private Flux<RagAskStreamEvent> emitAnswerFlow(
            AtomicInteger sequence,
            ContextState state,
            AtomicBoolean generatedByModel,
            StringBuilder answer,
            AtomicBoolean askFinalized
    ) {
        String correlationId = state.expanded().transformed().initial().correlationId();
        RagAskRequest request = state.expanded().transformed().initial().request();
        // 先把确定的上下文发给前端，再开始答案增量，前端可以提前渲染引用来源。
        RagAskStreamEvent contextsEvent = event(
                sequence,
                "contexts",
                correlationId,
                new RagContextsView(state.contexts().stream().map(this::toContextView).toList())
        );
        Flux<RagAskStreamEvent> answerEvents = answerGenerator
                .generateStream(request.question(), state.contexts(), generatedByModel::set)
                .concatMap(delta -> Mono.fromCallable(() -> {
                    answer.append(delta);
                    conversationService.streamAssistantAnswer(
                            state.expanded().transformed().initial().conversationState(),
                            answer.toString()
                    );
                    return event(sequence, "answer_delta", correlationId, new RagAnswerDeltaView(delta));
                }).subscribeOn(ragBlockingScheduler));
        return Flux.concat(
                Flux.just(contextsEvent),
                answerEvents,
                // notices 放在 answer 后发送，确保生成阶段产生的 fallback 也能被客户端收到。
                noticeEvents(sequence, correlationId, state.expanded().transformed().initial().feedbacks()),
                Mono.fromSupplier(() -> completedEvent(sequence, state, generatedByModel.get(), answer.toString(), askFinalized))
        );
    }

    private Flux<RagAskStreamEvent> noticeEvents(
            AtomicInteger sequence,
            String correlationId,
            Set<RagResponseNoticeView> feedbacks
    ) {
        return Flux.defer(() -> {
            RagRequestFeedbacks.restore(feedbacks);
            return Flux.fromIterable(RagRequestFeedbacks.snapshot())
                    .map(notice -> event(sequence, "notice", correlationId, notice));
        });
    }

    private RagAskStreamEvent completedEvent(
            AtomicInteger sequence,
            ContextState state,
            boolean generatedByModel,
            String answer,
            AtomicBoolean askFinalized
    ) {
        String correlationId = state.expanded().transformed().initial().correlationId();
        RagRequestFeedbacks.restore(state.expanded().transformed().initial().feedbacks());
        List<RagResponseNoticeView> notices = RagRequestFeedbacks.snapshot();
        boolean degraded = !notices.isEmpty();
        List<RagContextView> contextViews = state.contexts().stream().map(this::toContextView).toList();
        conversationService.completeAsk(
                state.expanded().transformed().initial().conversationState(),
                answer,
                state.expanded().transformed().transformedQuery().retrievalQuestion(),
                state.expanded().expandedQuery().retrievalQueries(),
                contextViews,
                notices,
                generatedByModel,
                degraded,
                correlationId
        );
        askFinalized.set(true);
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "rag.ask.completed")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, correlationId)
                .addKeyValue(RagLogFields.RAG_QUERY_COUNT, state.expanded().expandedQuery().retrievalQueries().size())
                .addKeyValue(RagLogFields.RAG_CONTEXT_COUNT, state.contexts().size())
                .addKeyValue(RagLogFields.RAG_GENERATED_BY_MODEL, generatedByModel)
                .addKeyValue(RagLogFields.RAG_QUERY_EXPANDED, state.expanded().expandedQuery().queryExpanded())
                .addKeyValue(RagLogFields.RAG_EXPANDED_BY_MODEL, state.expanded().expandedQuery().expandedByModel())
                .log("RAG ask completed");
        return event(
                sequence,
                "completed",
                correlationId,
                new RagAskCompletedView(generatedByModel, degraded, notices, state.contexts().size())
        );
    }

    private RagAskStreamEvent toTransformedEvent(AtomicInteger sequence, TransformedState state) {
        RagQueryTransformationResult result = state.transformedQuery();
        return event(
                sequence,
                "query_transformed",
                state.initial().correlationId(),
                new RagQueryTransformedView(
                        result.originalQuestion(),
                        result.retrievalQuestion(),
                        result.queryTransformed(),
                        result.transformedByModel(),
                        result.conversationTurns()
                )
        );
    }

    private RagAskStreamEvent toExpandedEvent(AtomicInteger sequence, ExpandedState state) {
        RagQueryExpansionResult result = state.expandedQuery();
        return event(
                sequence,
                "query_expanded",
                state.transformed().initial().correlationId(),
                new RagQueryExpandedView(
                        result.originalQuestion(),
                        result.retrievalQueries(),
                        result.queryExpanded(),
                        result.expandedByModel()
                )
        );
    }

    private RagAskStreamEvent toErrorEvent(
            AtomicInteger sequence,
            String correlationId,
            Set<RagResponseNoticeView> feedbacks,
            Throwable exception
    ) {
        log.error("RAG ask stream failed: correlationId={}", correlationId, exception);
        RagRequestFeedbacks.restore(feedbacks);
        RagRequestFeedbacks.record("ask", "error", "问答链路发生异常，流已终止。");
        return event(
                sequence,
                "error",
                correlationId,
                new RagStreamErrorView("error", RagLogHelper.errorSummary(exception), true)
        );
    }

    private RagAskStreamEvent event(AtomicInteger sequence, String eventName, String correlationId, Object data) {
        return new RagAskStreamEvent(
                correlationId + ":" + sequence.incrementAndGet(),
                eventName,
                correlationId,
                data
        );
    }

    private void logPreprocessed(
            TransformedState transformedState,
            RagQueryExpansionResult expandedQuery,
            RagRetrievalBudget budget
    ) {
        if (log.isDebugEnabled()) {
            log.atDebug()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.ask.preprocessed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, transformedState.initial().correlationId())
                    .addKeyValue("rag.conversation_turns", transformedState.transformedQuery().conversationTurns())
                    .addKeyValue("rag.query_transformed", transformedState.transformedQuery().queryTransformed())
                    .addKeyValue("rag.transformed_by_model", transformedState.transformedQuery().transformedByModel())
                    .addKeyValue(RagLogFields.RAG_QUERY_COUNT, expandedQuery.retrievalQueries().size())
                    .addKeyValue(RagLogFields.RAG_EXPANDED_BY_MODEL, expandedQuery.expandedByModel())
                    .addKeyValue("rag.answer_top_k", budget.answerTopK())
                    .addKeyValue("rag.per_query_top_k", budget.perQueryTopK())
                    .addKeyValue("rag.semantic_candidate_top_k", budget.semanticCandidateTopK())
                    .addKeyValue("rag.keyword_candidate_top_k", budget.keywordCandidateTopK())
                    .addKeyValue("rag.retrieval_budget_reason", budget.reason())
                    .addKeyValue(RagLogFields.RAG_QUERY_EXPANDED, expandedQuery.retrievalQueries())
                    .addKeyValue("rag.retrieval_queries", expandedQuery.retrievalQueries().stream()
                            .map(RagLogHelper::previewQuestion)
                            .toList())
                    .log("RAG query preprocessing completed");
        }
    }

    private boolean isTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<RagRetrievedChunk> expandNeighborWindow(List<RagRetrievedChunk> contexts) {
        int before = ragProperties.retrieval().neighborWindowBefore();
        int after = ragProperties.retrieval().neighborWindowAfter();
        if (contexts == null || contexts.isEmpty() || (before <= 0 && after <= 0)) {
            return contexts;
        }

        LinkedHashMap<String, RagRetrievedChunk> expanded = new LinkedHashMap<>();
        for (RagRetrievedChunk seed : contexts) {
            int seedIndex = seed.chunkIndex() == null ? 0 : seed.chunkIndex();
            int start = Math.max(0, seedIndex - Math.max(before, 0));
            int end = seedIndex + Math.max(after, 0);
            List<RagChunkSearchView> window = indexingChunkQueryFacade.findActiveChunksByDocumentIdAndRange(
                    seed.documentId(),
                    start,
                    end
            );

            if (window.isEmpty()) {
                expanded.putIfAbsent(chunkKey(seed.chunkId(), seed.documentId(), seed.chunkIndex()), seed);
                continue;
            }

            for (RagChunkSearchView row : window) {
                if (seed.chunkId() != null && seed.chunkId().equals(row.chunkId())) {
                    expanded.putIfAbsent(chunkKey(seed.chunkId(), seed.documentId(), seed.chunkIndex()), seed);
                    continue;
                }

                RagChunkMetadataView metadataView = metadataHelper.parse(row.metadata().toString());
                int distance = Math.abs((row.chunkIndex() == null ? seedIndex : row.chunkIndex()) - seedIndex);
                double baseScore = seed.score() == null ? 0.0d : seed.score();
                RagRetrievedChunk neighbor = new RagRetrievedChunk(
                        row.chunkId(),
                        row.documentId(),
                        row.title(),
                        row.sourceType(),
                        row.sourceUri(),
                        row.chunkIndex(),
                        Math.max(0.0d, baseScore - (distance * 0.0001d)),
                        row.chunkText(),
                        metadataView.headingPath(),
                        metadataView.blockType(),
                        metadataView.codeLanguage()
                );
                expanded.putIfAbsent(
                        chunkKey(neighbor.chunkId(), neighbor.documentId(), neighbor.chunkIndex()),
                        neighbor
                );
            }
        }

        log.debug(
                "RAG neighbor expansion completed: seedCount={}, expandedCount={}, before={}, after={}",
                contexts.size(),
                expanded.size(),
                before,
                after
        );
        return new ArrayList<>(expanded.values());
    }

    private String chunkKey(Long chunkId, Long documentId, Integer chunkIndex) {
        if (chunkId != null) {
            return "chunk:" + chunkId;
        }
        return "document:" + documentId + ":" + chunkIndex;
    }

    private RagContextView toContextView(RagRetrievedChunk chunk) {
        return new RagContextView(
                chunk.chunkId(),
                chunk.documentId(),
                chunk.title(),
                chunk.sourceType(),
                chunk.sourceUri(),
                chunk.chunkIndex(),
                chunk.score(),
                chunk.content(),
                chunk.headingPath(),
                chunk.blockType(),
                chunk.codeLanguage()
        );
    }

    private record AskInitialState(
            RagAskRequest request,
            String correlationId,
            int topK,
            RagSearchFilter filter,
            boolean hasFilter,
            Set<RagResponseNoticeView> feedbacks,
            RagConversationService.AskConversationState conversationState
    ) {
    }

    private record TransformedState(
            AskInitialState initial,
            RagQueryTransformationResult transformedQuery
    ) {
    }

    private record ExpandedState(
            TransformedState transformed,
            RagQueryExpansionResult expandedQuery,
            RagRetrievalBudget budget
    ) {
    }

    private record ContextState(
            ExpandedState expanded,
            List<RagRetrievedChunk> contexts
    ) {
    }
}
