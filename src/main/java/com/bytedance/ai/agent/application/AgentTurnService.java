package com.bytedance.ai.agent.application;

import com.bytedance.ai.agent.answer.CitationExtractor;
import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnFacade;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.memory.ConversationSummary;
import com.bytedance.ai.agent.persistence.AgentTurnPersistenceService;
import com.bytedance.ai.agent.persistence.AgentTurnRecord;
import com.bytedance.ai.infrastructure.config.RagConcurrencyConfiguration;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState;
import com.bytedance.ai.shared.support.RagLogFields;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AgentTurnService implements AgentTurnFacade {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnService.class);
    private static final String MODEL_NAME = "agent-answer-v1";

    private final AgentTurnPersistenceService persistenceService;
    private final ConversationTurnAdapter conversationTurnAdapter;
    private final CitationExtractor citationExtractor;
    private final AgentSseEventFactory eventFactory;
    private final AgentWorkflowService agentWorkflowService;
    private final Scheduler ragBlockingScheduler;

    public AgentTurnService(
            AgentTurnPersistenceService persistenceService,
            ConversationTurnAdapter conversationTurnAdapter,
            CitationExtractor citationExtractor,
            AgentSseEventFactory eventFactory,
            AgentWorkflowService agentWorkflowService,
            @Qualifier(RagConcurrencyConfiguration.RAG_BLOCKING_SCHEDULER) Scheduler ragBlockingScheduler
    ) {
        this.persistenceService = persistenceService;
        this.conversationTurnAdapter = conversationTurnAdapter;
        this.citationExtractor = citationExtractor;
        this.eventFactory = eventFactory;
        this.agentWorkflowService = agentWorkflowService;
        this.ragBlockingScheduler = ragBlockingScheduler;
    }

    @Override
    public Flux<AgentStreamEvent> turnStream(AgentTurnRequest request) {
        AgentTurnRequest normalizedRequest = normalizeRequest(request);
        return Mono.fromCallable(() -> prepareWorkflowTurn(normalizedRequest))
                .subscribeOn(ragBlockingScheduler)
                .flatMapMany(this::streamWorkflowTurn);
    }

    private static AgentTurnRequest normalizeRequest(AgentTurnRequest request) {
        return new AgentTurnRequest(
                request.userId(),
                request.conversationId(),
                request.message(),
                normalizeClientId(request.turnId()),
                normalizeClientId(request.requestId()),
                normalizeClientId(request.imageRef()),
                request.history()
        );
    }

    private static String normalizeClientId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().replace("\\\"", "\"");
        for (int i = 0; i < 3 && normalized.length() >= 2; i++) {
            if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
                normalized = normalized.substring(1, normalized.length() - 1).trim();
            }
        }
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private PreparedWorkflowTurn prepareWorkflowTurn(AgentTurnRequest request) {
        TurnExecutionState state = new TurnExecutionState(request);
        try {
            logTurnStarted(state);
            long stageStarted = System.nanoTime();
            Optional<AgentTurnRecord> existing = findExistingTurn(state);
            logStageCompleted(state, "idempotency_check", stageStarted, "hit", existing.isPresent());
            if (existing.isPresent()) {
                logReplayTurn(state, existing.get());
                return PreparedWorkflowTurn.replay(replayExisting(existing.get()));
            }

            stageStarted = System.nanoTime();
            persistenceService.createRunning(
                    state.turnId,
                    state.correlationId,
                    request.userId(),
                    request.conversationId(),
                    state.requestId,
                    request.message()
            );
            logStageCompleted(state, "persistence_create_running", stageStarted, null, null);

            stageStarted = System.nanoTime();
            AgentTurnConversationState conversationState = conversationTurnAdapter.begin(
                    request.userId(),
                    request.conversationId(),
                    request.message(),
                    state.correlationId
            );
            state.assistantMessageId = conversationState.assistantMessageId();
            logStageCompleted(state, "conversation_begin", stageStarted, "historySize", conversationState.history().size());

            stageStarted = System.nanoTime();
            persistenceService.attachConversationMessages(
                    state.turnId,
                    conversationState.userMessageId(),
                    conversationState.assistantMessageId()
            );
            logStageCompleted(state, "persistence_attach_messages", stageStarted, null, null);

            return PreparedWorkflowTurn.active(
                    state,
                    conversationState,
                    List.of(eventFactory.turnStarted(state.correlationId, state.turnId, request.conversationId(), MODEL_NAME))
            );
        } catch (Exception exception) {
            return PreparedWorkflowTurn.failed(state, exception);
        }
    }

    private Flux<AgentStreamEvent> streamWorkflowTurn(PreparedWorkflowTurn prepared) {
        if (prepared.replayEvents != null) {
            return Flux.fromIterable(prepared.replayEvents);
        }
        if (prepared.failure != null) {
            return failTurn(prepared.state, prepared.failure);
        }

        TurnExecutionState state = prepared.state;
        AtomicReference<AgentWorkflowService.WorkflowResult> resultRef = new AtomicReference<>();
        Flux<AgentStreamEvent> workflowEvents = agentWorkflowService.run(new AgentWorkflowService.WorkflowRequest(
                        state.request,
                        state.turnId,
                        state.correlationId,
                        prepared.conversationState
                ))
                .subscribeOn(ragBlockingScheduler)
                .concatMap(signal -> {
                    if (signal.event() != null) {
                        return Flux.just(signal.event());
                    }
                    AgentWorkflowService.WorkflowResult result = signal.result();
                    resultRef.set(result);
                    state.generatedByModel.set(result.generatedByModel());
                    state.memorySummary = result.memorySummary();
                    state.answerText.append(result.answerText());
                    return citationExtractor.toAnswerEvents(
                            Flux.just(result.answerText()).filter(StringUtils::hasText),
                            result.cards(),
                            state.correlationId,
                            eventFactory
                    );
                });
        Mono<AgentStreamEvent> completed = Mono.fromCallable(() -> completeWorkflowTurn(state, resultRef.get()))
                .subscribeOn(ragBlockingScheduler);
        return Flux.concat(Flux.fromIterable(prepared.prefixEvents), workflowEvents, completed)
                .onErrorResume(exception -> failTurn(state, exception));
    }

    private AgentStreamEvent completeWorkflowTurn(TurnExecutionState state, AgentWorkflowService.WorkflowResult result) {
        if (result == null) {
            throw new IllegalStateException("agent workflow completed without result");
        }
        long stageStarted = System.nanoTime();
        persistenceService.recordIntent(
                state.turnId,
                result.intent().name(),
                "workflow",
                null,
                result.slots()
        );
        logStageCompleted(state, "persistence_record_intent", stageStarted, null, null);

        stageStarted = System.nanoTime();
        persistenceService.recordToolState(state.turnId, result.toolCalls(), result.cards());
        logStageCompleted(state, "persistence_record_tool_state", stageStarted, "cardsCount", result.cards().size());

        return completeTurn(state);
    }

    private Optional<AgentTurnRecord> findExistingTurn(TurnExecutionState state) {
        Optional<AgentTurnRecord> byTurnId = persistenceService.findByTurnId(state.turnId);
        if (byTurnId.isPresent()) {
            return byTurnId;
        }
        return persistenceService.findByRequestId(state.request.userId(), state.request.conversationId(), state.requestId);
    }

    private List<AgentStreamEvent> replayExisting(AgentTurnRecord record) {
        if ("FAILED".equals(record.status())) {
            return List.of(eventFactory.turnError(
                    record.correlationId(),
                    record.errorCode() == null ? "AGENT_TURN_FAILED" : record.errorCode(),
                    record.errorMessage() == null ? "历史 turn 已失败" : record.errorMessage(),
                    false
            ));
        }
        return List.of(eventFactory.turnCompleted(
                record.correlationId(),
                record.turnId(),
                record.latencyMs(),
                record.tokensIn(),
                record.tokensOut(),
                Boolean.TRUE.equals(record.generatedByModel())
        ));
    }

    private AgentStreamEvent completeTurn(TurnExecutionState state) {
        String answer = state.answerText.toString();
        if (StringUtils.hasText(state.assistantMessageId)) {
            long stageStarted = System.nanoTime();
            conversationTurnAdapter.complete(state.assistantMessageId, answer);
            logStageCompleted(state, "conversation_complete", stageStarted, null, null);
        }
        int latencyMs = (int) Duration.ofNanos(System.nanoTime() - state.startedNanos).toMillis();
        long stageStarted = System.nanoTime();
        persistenceService.markSucceeded(
                state.turnId,
                answer,
                state.generatedByModel.get(),
                null,
                null,
                latencyMs,
                state.memorySummary == null ? null : state.memorySummary.summary().orElse(null),
                state.memorySummary == null ? null : state.memorySummary.messageCount(),
                state.memorySummary == null ? null : state.memorySummary.model()
        );
        logStageCompleted(state, "persistence_mark_succeeded", stageStarted, null, null);
        logTurnCompleted(state, latencyMs);
        return eventFactory.turnCompleted(state.correlationId, state.turnId, latencyMs, null, null, state.generatedByModel.get());
    }

    private Flux<AgentStreamEvent> failTurn(TurnExecutionState state, Throwable exception) {
        return Mono.fromCallable(() -> failTurnBlocking(state, exception))
                .subscribeOn(ragBlockingScheduler)
                .flux();
    }

    private AgentStreamEvent failTurnBlocking(TurnExecutionState state, Throwable exception) {
        String message = RagLogHelper.errorSummary(exception);
        log.atWarn()
                .addKeyValue(RagLogFields.EVENT_NAME, "agent.turn.failed")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.correlationId)
                .addKeyValue("agent.turn_id", state.turnId)
                .addKeyValue("agent.conversation_id", state.request.conversationId())
                .addKeyValue("agent.user_id", state.request.userId())
                .addKeyValue(RagLogFields.RAG_ELAPSED_MS, elapsedMs(state.startedNanos))
                .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, message)
                .log("agent.turn failed: turnId={}, conversationId={}, tookMs={}, error={}",
                        state.turnId,
                        state.request.conversationId(),
                        elapsedMs(state.startedNanos),
                        message,
                        exception);
        if (StringUtils.hasText(state.assistantMessageId)) {
            try {
                long stageStarted = System.nanoTime();
                conversationTurnAdapter.fail(state.assistantMessageId, "AGENT_TURN_ERROR", message);
                logStageCompleted(state, "conversation_fail", stageStarted, null, null);
            } catch (Exception failException) {
                log.warn("failed to mark conversation turn failed: error={}", RagLogHelper.errorSummary(failException));
            }
        }
        try {
            int latencyMs = (int) Duration.ofNanos(System.nanoTime() - state.startedNanos).toMillis();
            long stageStarted = System.nanoTime();
            persistenceService.markFailed(state.turnId, "AGENT_TURN_ERROR", message, latencyMs);
            logStageCompleted(state, "persistence_mark_failed", stageStarted, null, null);
        } catch (Exception failException) {
            log.warn("failed to mark agent turn failed: error={}", RagLogHelper.errorSummary(failException));
        }
        return eventFactory.turnError(state.correlationId, "AGENT_TURN_ERROR", message, false);
    }

    private void logTurnStarted(TurnExecutionState state) {
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "agent.turn.received")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_STARTED)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.correlationId)
                .addKeyValue("agent.turn_id", state.turnId)
                .addKeyValue("agent.request_id", state.requestId)
                .addKeyValue("agent.conversation_id", state.request.conversationId())
                .addKeyValue("agent.user_id", state.request.userId())
                .addKeyValue(RagLogFields.RAG_QUESTION_LENGTH, safeLength(state.request.message()))
                .addKeyValue(RagLogFields.RAG_QUESTION_PREVIEW, RagLogHelper.previewQuestion(state.request.message()))
                .log("agent.turn received: turnId={}, conversationId={}, userId={}, qPreview={}",
                        state.turnId,
                        state.request.conversationId(),
                        state.request.userId(),
                        RagLogHelper.previewQuestion(state.request.message()));
    }

    private void logReplayTurn(TurnExecutionState state, AgentTurnRecord record) {
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "agent.turn.replayed")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SKIPPED)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.correlationId)
                .addKeyValue("agent.turn_id", state.turnId)
                .addKeyValue("agent.record_turn_id", record.turnId())
                .addKeyValue("agent.status", record.status())
                .addKeyValue(RagLogFields.RAG_ELAPSED_MS, elapsedMs(state.startedNanos))
                .log("agent.turn replayed: turnId={}, recordTurnId={}, status={}, tookMs={}",
                        state.turnId,
                        record.turnId(),
                        record.status(),
                        elapsedMs(state.startedNanos));
    }

    private void logStageCompleted(TurnExecutionState state, String stage, long startedNanos, String extraKey, Object extraValue) {
        var builder = log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "agent.turn.stage.completed")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.correlationId)
                .addKeyValue("agent.turn_id", state.turnId)
                .addKeyValue("agent.conversation_id", state.request.conversationId())
                .addKeyValue("agent.stage", stage)
                .addKeyValue(RagLogFields.RAG_ELAPSED_MS, elapsedMs(startedNanos));
        if (extraKey != null) {
            builder.addKeyValue("agent." + extraKey, extraValue);
            builder.log("agent.turn stage completed: turnId={}, stage={}, tookMs={}, {}={}",
                    state.turnId,
                    stage,
                    elapsedMs(startedNanos),
                    extraKey,
                    extraValue);
            return;
        }
        builder.log("agent.turn stage completed: turnId={}, stage={}, tookMs={}",
                state.turnId,
                stage,
                elapsedMs(startedNanos));
    }

    private void logTurnCompleted(TurnExecutionState state, int latencyMs) {
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "agent.turn.completed")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, state.correlationId)
                .addKeyValue("agent.turn_id", state.turnId)
                .addKeyValue("agent.conversation_id", state.request.conversationId())
                .addKeyValue("agent.user_id", state.request.userId())
                .addKeyValue("agent.answer_length", state.answerText.length())
                .addKeyValue(RagLogFields.RAG_GENERATED_BY_MODEL, state.generatedByModel.get())
                .addKeyValue(RagLogFields.RAG_ELAPSED_MS, latencyMs)
                .log("agent.turn completed: turnId={}, conversationId={}, tookMs={}, generatedByModel={}, answerLength={}",
                        state.turnId,
                        state.request.conversationId(),
                        latencyMs,
                        state.generatedByModel.get(),
                        state.answerText.length());
    }

    private long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private record PreparedWorkflowTurn(
            TurnExecutionState state,
            AgentTurnConversationState conversationState,
            List<AgentStreamEvent> prefixEvents,
            List<AgentStreamEvent> replayEvents,
            Throwable failure
    ) {
        private PreparedWorkflowTurn {
            prefixEvents = prefixEvents == null ? List.of() : List.copyOf(prefixEvents);
            replayEvents = replayEvents == null ? null : List.copyOf(replayEvents);
        }

        private static PreparedWorkflowTurn active(
                TurnExecutionState state,
                AgentTurnConversationState conversationState,
                List<AgentStreamEvent> prefixEvents
        ) {
            return new PreparedWorkflowTurn(state, conversationState, prefixEvents, null, null);
        }

        private static PreparedWorkflowTurn replay(List<AgentStreamEvent> replayEvents) {
            return new PreparedWorkflowTurn(null, null, List.of(), replayEvents, null);
        }

        private static PreparedWorkflowTurn failed(TurnExecutionState state, Throwable failure) {
            return new PreparedWorkflowTurn(state, null, List.of(), null, failure);
        }
    }

    private static class TurnExecutionState {
        private final AgentTurnRequest request;
        private final String turnId;
        private final String requestId;
        private final String correlationId;
        private final long startedNanos = System.nanoTime();
        private final StringBuilder answerText = new StringBuilder();
        private final AtomicBoolean generatedByModel = new AtomicBoolean(false);
        private ConversationSummary memorySummary = ConversationSummary.empty();
        private String assistantMessageId;

        private TurnExecutionState(AgentTurnRequest request) {
            this.request = request;
            String normalizedTurnId = normalizeClientId(request.turnId());
            String normalizedRequestId = normalizeClientId(request.requestId());
            this.turnId = StringUtils.hasText(normalizedTurnId) ? normalizedTurnId : UUID.randomUUID().toString();
            this.requestId = StringUtils.hasText(normalizedRequestId) ? normalizedRequestId : this.turnId;
            this.correlationId = UUID.randomUUID().toString();
        }
    }
}
