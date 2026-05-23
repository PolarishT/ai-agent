package com.bytedance.ai.agent.application;

import com.bytedance.ai.agent.answer.AgentAnswerGenerator;
import com.bytedance.ai.agent.answer.CitationExtractor;
import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnFacade;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.api.ToolCallView;
import com.bytedance.ai.agent.intent.IntentClassification;
import com.bytedance.ai.agent.intent.IntentClassifier;
import com.bytedance.ai.agent.persistence.AgentTurnPersistenceService;
import com.bytedance.ai.agent.persistence.AgentTurnRecord;
import com.bytedance.ai.agent.slot.SlotExtractor;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.agent.tool.ToolRegistry;
import com.bytedance.ai.agent.tool.impl.SearchProductsToolCallback;
import com.bytedance.ai.infrastructure.config.RagConcurrencyConfiguration;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState;
import com.bytedance.ai.shared.support.RagJsonCodec;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentTurnService implements AgentTurnFacade {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnService.class);
    private static final String MODEL_NAME = "agent-answer-v1";

    private final AgentTurnPersistenceService persistenceService;
    private final ConversationTurnAdapter conversationTurnAdapter;
    private final IntentClassifier intentClassifier;
    private final SlotExtractor slotExtractor;
    private final ToolRegistry toolRegistry;
    private final AgentAnswerGenerator answerGenerator;
    private final CitationExtractor citationExtractor;
    private final AgentSseEventFactory eventFactory;
    private final RagJsonCodec jsonCodec;
    private final Scheduler ragBlockingScheduler;

    public AgentTurnService(
            AgentTurnPersistenceService persistenceService,
            ConversationTurnAdapter conversationTurnAdapter,
            IntentClassifier intentClassifier,
            SlotExtractor slotExtractor,
            ToolRegistry toolRegistry,
            AgentAnswerGenerator answerGenerator,
            CitationExtractor citationExtractor,
            AgentSseEventFactory eventFactory,
            RagJsonCodec jsonCodec,
            @Qualifier(RagConcurrencyConfiguration.RAG_BLOCKING_SCHEDULER) Scheduler ragBlockingScheduler
    ) {
        this.persistenceService = persistenceService;
        this.conversationTurnAdapter = conversationTurnAdapter;
        this.intentClassifier = intentClassifier;
        this.slotExtractor = slotExtractor;
        this.toolRegistry = toolRegistry;
        this.answerGenerator = answerGenerator;
        this.citationExtractor = citationExtractor;
        this.eventFactory = eventFactory;
        this.jsonCodec = jsonCodec;
        this.ragBlockingScheduler = ragBlockingScheduler;
    }

    @Override
    public Flux<AgentStreamEvent> turnStream(AgentTurnRequest request) {
        return Flux.defer(() -> execute(request))
                .subscribeOn(ragBlockingScheduler);
    }

    private Flux<AgentStreamEvent> execute(AgentTurnRequest request) {
        TurnExecutionState state = new TurnExecutionState(request);
        Optional<AgentTurnRecord> existing = findExistingTurn(state);
        if (existing.isPresent()) {
            return replayExisting(existing.get());
        }

        try {
            persistenceService.createRunning(
                    state.turnId,
                    state.correlationId,
                    request.userId(),
                    request.conversationId(),
                    state.requestId,
                    request.message()
            );
            AgentTurnConversationState conversationState = conversationTurnAdapter.begin(
                    request.userId(),
                    request.conversationId(),
                    request.message(),
                    state.correlationId
            );
            state.assistantMessageId = conversationState.assistantMessageId();
            persistenceService.attachConversationMessages(
                    state.turnId,
                    conversationState.userMessageId(),
                    conversationState.assistantMessageId()
            );

            List<AgentStreamEvent> prefixEvents = new ArrayList<>();
            prefixEvents.add(eventFactory.turnStarted(state.correlationId, state.turnId, request.conversationId(), MODEL_NAME));

            IntentClassification classification = intentClassifier.classify(request.message());
            Slot slots = slotExtractor.extract(request.message(), classification.intent());
            persistenceService.recordIntent(
                    state.turnId,
                    classification.intent().name(),
                    classification.source(),
                    classification.confidence(),
                    slots
            );
            prefixEvents.add(eventFactory.intentDetected(
                    state.correlationId,
                    classification.intent(),
                    classification.confidence(),
                    classification.source(),
                    slots
            ));

            List<SpuCardView> cards = new ArrayList<>();
            List<ToolCallView> toolCalls = new ArrayList<>();
            if (classification.intent() == IntentType.OUT_OF_SCOPE) {
                persistenceService.recordToolState(state.turnId, toolCalls, cards);
            } else {
                executeTools(state, classification.intent(), slots, prefixEvents, cards, toolCalls);
                persistenceService.recordToolState(state.turnId, toolCalls, cards);
                if (cards.isEmpty()) {
                    prefixEvents.add(eventFactory.notice(
                            state.correlationId,
                            "NO_PRODUCT_MATCH",
                            "未检索到可展示商品卡片，回答将引导用户补充需求。",
                            "info"
                    ));
                }
            }

            Flux<String> answerStream = answerStream(request, classification.intent(), cards, state.generatedByModel);
            Flux<AgentStreamEvent> answerEvents = citationExtractor.toAnswerEvents(
                    answerStream.doOnNext(state.answerText::append),
                    cards,
                    state.correlationId,
                    eventFactory
            );
            Mono<AgentStreamEvent> completed = Mono.fromSupplier(() -> completeTurn(state));
            return Flux.concat(Flux.fromIterable(prefixEvents), answerEvents, completed)
                    .onErrorResume(exception -> failTurn(state, exception));
        } catch (Exception exception) {
            return failTurn(state, exception);
        }
    }

    private Optional<AgentTurnRecord> findExistingTurn(TurnExecutionState state) {
        Optional<AgentTurnRecord> byTurnId = persistenceService.findByTurnId(state.turnId);
        if (byTurnId.isPresent()) {
            return byTurnId;
        }
        return persistenceService.findByRequestId(state.request.userId(), state.request.conversationId(), state.requestId);
    }

    private Flux<AgentStreamEvent> replayExisting(AgentTurnRecord record) {
        if ("FAILED".equals(record.status())) {
            return Flux.just(eventFactory.turnError(
                    record.correlationId(),
                    record.errorCode() == null ? "AGENT_TURN_FAILED" : record.errorCode(),
                    record.errorMessage() == null ? "历史 turn 已失败" : record.errorMessage(),
                    false
            ));
        }
        return Flux.just(eventFactory.turnCompleted(
                record.correlationId(),
                record.turnId(),
                record.latencyMs(),
                record.tokensIn(),
                record.tokensOut(),
                Boolean.TRUE.equals(record.generatedByModel())
        ));
    }

    private void executeTools(
            TurnExecutionState state,
            IntentType intent,
            Slot slots,
            List<AgentStreamEvent> prefixEvents,
            List<SpuCardView> cards,
            List<ToolCallView> toolCalls
    ) {
        for (AgentToolCallback callback : toolRegistry.plan(intent)) {
            Map<String, Object> args = toolArgs(state.request.message(), slots);
            String toolName = callback.getToolDefinition().name();
            prefixEvents.add(eventFactory.toolCalling(state.correlationId, toolName, args));
            long started = System.nanoTime();
            if (callback instanceof SearchProductsToolCallback searchProductsTool) {
                SearchProductsToolCallback.SearchProductsOutput output = searchProductsTool.search(
                        new SearchProductsToolCallback.SearchProductsInput(
                                state.request.message(),
                                slots,
                                10,
                                List.of()
                        )
                );
                cards.addAll(output.cards());
                long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                toolCalls.add(new ToolCallView(intent, toolName, args, latencyMs));
                prefixEvents.add(eventFactory.toolResult(
                        state.correlationId,
                        output.toolName(),
                        output.cards(),
                        output.facetsApplied()
                ));
            } else {
                callback.call(jsonCodec.write(args));
                long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                toolCalls.add(new ToolCallView(intent, toolName, args, latencyMs));
            }
        }
    }

    private Map<String, Object> toolArgs(String message, Slot slots) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", message);
        args.put("slots", slots);
        args.put("topK", 10);
        args.put("includeChunkTypes", List.of());
        return args;
    }

    private Flux<String> answerStream(
            AgentTurnRequest request,
            IntentType intent,
            List<SpuCardView> cards,
            AtomicBoolean generatedByModel
    ) {
        if (intent == IntentType.OUT_OF_SCOPE) {
            generatedByModel.set(false);
            return Flux.just("我只能帮助你挑选和比较商品，暂时不能处理这个请求。你可以告诉我预算、品类或使用场景。");
        }
        return answerGenerator.generateStream(request.message(), cards, generatedByModel::set);
    }

    private AgentStreamEvent completeTurn(TurnExecutionState state) {
        String answer = state.answerText.toString();
        if (StringUtils.hasText(state.assistantMessageId)) {
            conversationTurnAdapter.complete(state.assistantMessageId, answer);
        }
        int latencyMs = (int) Duration.ofNanos(System.nanoTime() - state.startedNanos).toMillis();
        persistenceService.markSucceeded(state.turnId, answer, state.generatedByModel.get(), null, null, latencyMs);
        return eventFactory.turnCompleted(state.correlationId, state.turnId, latencyMs, null, null, state.generatedByModel.get());
    }

    private Flux<AgentStreamEvent> failTurn(TurnExecutionState state, Throwable exception) {
        String message = RagLogHelper.errorSummary(exception);
        log.warn("agent turn failed: turnId={}, error={}", state.turnId, message, exception);
        if (StringUtils.hasText(state.assistantMessageId)) {
            try {
                conversationTurnAdapter.fail(state.assistantMessageId, "AGENT_TURN_ERROR", message);
            } catch (Exception failException) {
                log.warn("failed to mark conversation turn failed: error={}", RagLogHelper.errorSummary(failException));
            }
        }
        try {
            int latencyMs = (int) Duration.ofNanos(System.nanoTime() - state.startedNanos).toMillis();
            persistenceService.markFailed(state.turnId, "AGENT_TURN_ERROR", message, latencyMs);
        } catch (Exception failException) {
            log.warn("failed to mark agent turn failed: error={}", RagLogHelper.errorSummary(failException));
        }
        return Flux.just(eventFactory.turnError(state.correlationId, "AGENT_TURN_ERROR", message, false));
    }

    private static class TurnExecutionState {
        private final AgentTurnRequest request;
        private final String turnId;
        private final String requestId;
        private final String correlationId;
        private final long startedNanos = System.nanoTime();
        private final StringBuilder answerText = new StringBuilder();
        private final AtomicBoolean generatedByModel = new AtomicBoolean(false);
        private String assistantMessageId;

        private TurnExecutionState(AgentTurnRequest request) {
            this.request = request;
            this.turnId = StringUtils.hasText(request.turnId()) ? request.turnId() : UUID.randomUUID().toString();
            this.requestId = StringUtils.hasText(request.requestId()) ? request.requestId() : this.turnId;
            this.correlationId = UUID.randomUUID().toString();
        }
    }
}
