package com.bytedance.ai.agent.application;

import com.bytedance.ai.agent.answer.AgentAnswerGenerator;
import com.bytedance.ai.agent.answer.CitationExtractor;
import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnFacade;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.api.ToolCallView;
import com.bytedance.ai.agent.intent.IntentClassification;
import com.bytedance.ai.agent.intent.IntentClassifier;
import com.bytedance.ai.agent.memory.ConversationMemory;
import com.bytedance.ai.agent.memory.ConversationMemoryLoader;
import com.bytedance.ai.agent.memory.ConversationSummarizer;
import com.bytedance.ai.agent.memory.ConversationSummary;
import com.bytedance.ai.agent.persistence.AgentTurnPersistenceService;
import com.bytedance.ai.agent.persistence.AgentTurnRecord;
import com.bytedance.ai.agent.slot.NegationSlotExtractor;
import com.bytedance.ai.agent.slot.SlotExtractor;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.agent.tool.ToolRegistry;
import com.bytedance.ai.agent.tool.impl.CompareProductsToolCallback;
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

/**
 * Agent 主流程编排，串起一次会话 turn 的完整链路：
 * <pre>
 * 幂等检查 → 写 agent_turn(RUNNING) → 写会话消息对(beginTurn) → 加载 ConversationMemory
 *   → 触发 ConversationSummarizer（每 N 轮）→ 规则意图分类 + 主 slot 抽取
 *   → NegationSlotExtractor 并入反选 mustNot → 工具执行（search / compare）
 *   → LLM 流式生成答案 + [#N] 引用抽取 → markSucceeded
 * </pre>
 *
 * <p>异常路径：任意阶段抛出未捕获异常都会进 {@link #failTurn}，落 agent_turn(FAILED) +
 * 发 {@code turn.error} 事件；上游 SSE 客户端凭此切换降级文案。
 *
 * <p>幂等：通过 {@code (userId, conversationId, requestId)} OR {@code turnId} 命中既有行时，
 * 直接回放 {@code turn.completed} / {@code turn.error}，不会重复触发 LLM 与检索。
 */
@Service
public class AgentTurnService implements AgentTurnFacade {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnService.class);
    /** SSE {@code turn.started.model} 字段使用的版本号；prompt 改版时一并升版便于客户端识别行为差异。 */
    private static final String MODEL_NAME = "agent-answer-v1";

    private final AgentTurnPersistenceService persistenceService;
    private final ConversationTurnAdapter conversationTurnAdapter;
    private final ConversationMemoryLoader memoryLoader;
    private final ConversationSummarizer conversationSummarizer;
    private final IntentClassifier intentClassifier;
    private final SlotExtractor slotExtractor;
    private final NegationSlotExtractor negationSlotExtractor;
    private final ToolRegistry toolRegistry;
    private final AgentAnswerGenerator answerGenerator;
    private final CitationExtractor citationExtractor;
    private final AgentSseEventFactory eventFactory;
    private final RagJsonCodec jsonCodec;
    private final Scheduler ragBlockingScheduler;

    public AgentTurnService(
            AgentTurnPersistenceService persistenceService,
            ConversationTurnAdapter conversationTurnAdapter,
            ConversationMemoryLoader memoryLoader,
            ConversationSummarizer conversationSummarizer,
            IntentClassifier intentClassifier,
            SlotExtractor slotExtractor,
            NegationSlotExtractor negationSlotExtractor,
            ToolRegistry toolRegistry,
            AgentAnswerGenerator answerGenerator,
            CitationExtractor citationExtractor,
            AgentSseEventFactory eventFactory,
            RagJsonCodec jsonCodec,
            @Qualifier(RagConcurrencyConfiguration.RAG_BLOCKING_SCHEDULER) Scheduler ragBlockingScheduler
    ) {
        this.persistenceService = persistenceService;
        this.conversationTurnAdapter = conversationTurnAdapter;
        this.memoryLoader = memoryLoader;
        this.conversationSummarizer = conversationSummarizer;
        this.intentClassifier = intentClassifier;
        this.slotExtractor = slotExtractor;
        this.negationSlotExtractor = negationSlotExtractor;
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

            ConversationMemory memory = memoryLoader.load(request.conversationId(), conversationState.history());
            ConversationSummary summary = conversationSummarizer.summarize(
                    conversationState.history(),
                    memory.summary(),
                    memory.summaryMessageCount()
            );
            memory = memory.withSummary(summary);
            state.memorySummary = summary;
            List<AgentStreamEvent> prefixEvents = new ArrayList<>();
            prefixEvents.add(eventFactory.turnStarted(state.correlationId, state.turnId, request.conversationId(), MODEL_NAME));

            IntentClassification classification = intentClassifier.classify(request.message(), memory);
            Slot slots = slotExtractor.extract(request.message(), classification.intent(), memory);
            // 反选语义独立抽取后并入 slot.mustNot；OOS 跳过。
            if (classification.intent() != IntentType.OUT_OF_SCOPE) {
                Slot.MustNot negation = negationSlotExtractor.extract(request.message());
                if (!negation.isEmpty()) {
                    slots = slots.withMustNot(slots.mustNot().merge(negation));
                }
            }
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
                executeTools(state, classification.intent(), slots, memory, prefixEvents, cards, toolCalls);
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

            Flux<String> answerStream = answerStream(request, classification.intent(), cards, state.compareMatrix, memory, state.generatedByModel);
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

    /**
     * 幂等查找：客户端可能用同一个 turnId 重试，也可能换 turnId 但带相同 requestId。
     * 两条都查命中即视为既有 turn，跳过整条主流程直接回放。
     */
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

    /**
     * W1 阶段不让 LLM 选 tool，按 intent → toolRegistry 程序确定性调用。
     *
     * <p>REFINE intent 时把上一轮命中的 SPU externalRef 透传给 search_products，
     * 让检索把召回范围收敛到子集；其它 intent 保持全库检索。
     * 反选 mustNot 不在这里关心，已经在 main slot 阶段并入 {@code slots.mustNot()}。
     */
    private void executeTools(
            TurnExecutionState state,
            IntentType intent,
            Slot slots,
            ConversationMemory memory,
            List<AgentStreamEvent> prefixEvents,
            List<SpuCardView> cards,
            List<ToolCallView> toolCalls
    ) {
        // REFINE：把候选锁到上一轮 SPU 列表，相当于"在这几个里再筛"。
        List<String> restrictToSpuRefs = intent == IntentType.REFINE && memory != null
                ? memory.lastTurnSpuRefs()
                : List.of();
        for (AgentToolCallback callback : toolRegistry.plan(intent)) {
            Map<String, Object> args = toolArgs(state.request.message(), slots, restrictToSpuRefs);
            String toolName = callback.getToolDefinition().name();
            prefixEvents.add(eventFactory.toolCalling(state.correlationId, toolName, args));
            long started = System.nanoTime();
            if (callback instanceof SearchProductsToolCallback searchProductsTool) {
                SearchProductsToolCallback.SearchProductsOutput output = searchProductsTool.search(
                        new SearchProductsToolCallback.SearchProductsInput(
                                state.request.message(),
                                slots,
                                10,
                                List.of(),
                                restrictToSpuRefs
                        )
                );
                cards.addAll(output.cards());
                long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                toolCalls.add(new ToolCallView(intent, toolName, args, latencyMs));
                prefixEvents.add(eventFactory.toolResult(
                        state.correlationId,
                        output.toolName(),
                        output.cards(),
                        output.facetsApplied(),
                        output.excludedFacets()
                ));
            } else if (callback instanceof CompareProductsToolCallback compareProductsTool) {
                CompareProductsToolCallback.CompareProductsOutput output = compareProductsTool.compare(
                        new CompareProductsToolCallback.CompareProductsInput(
                                state.request.message(),
                                List.of(),
                                List.of(),
                                3,
                                compareAspects(state.request.message())
                        )
                );
                cards.addAll(output.cards());
                state.compareMatrix = output.compareMatrix();
                long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                toolCalls.add(new ToolCallView(intent, toolName, args, latencyMs));
                prefixEvents.add(eventFactory.toolResult(
                        state.correlationId,
                        output.toolName(),
                        output.cards(),
                        Map.of(),
                        output.compareMatrix()
                ));
            } else {
                callback.call(jsonCodec.write(args));
                long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                toolCalls.add(new ToolCallView(intent, toolName, args, latencyMs));
            }
        }
    }

    private Map<String, Object> toolArgs(String message, Slot slots, List<String> restrictToSpuRefs) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", message);
        args.put("slots", slots);
        args.put("topK", 10);
        args.put("includeChunkTypes", List.of());
        args.put("restrictToSpuRefs", restrictToSpuRefs == null ? List.of() : restrictToSpuRefs);
        args.put("compareAspects", compareAspects(message));
        return args;
    }

    /**
     * COMPARE 工具的"对比维度"提示，W1 用最朴素的关键词命中；
     * W3 接入对比矩阵 LLM-driven 选维度后会被替换。
     */
    private List<String> compareAspects(String message) {
        List<String> aspects = new ArrayList<>();
        if (message != null && message.contains("保湿")) {
            aspects.add("保湿");
        }
        if (message != null && message.contains("性价比")) {
            aspects.add("性价比");
        }
        return aspects;
    }

    private Flux<String> answerStream(
            AgentTurnRequest request,
            IntentType intent,
            List<SpuCardView> cards,
            CompareMatrixView compareMatrix,
            ConversationMemory memory,
            AtomicBoolean generatedByModel
    ) {
        if (intent == IntentType.OUT_OF_SCOPE) {
            generatedByModel.set(false);
            return Flux.just("我只能帮助你挑选和比较商品，暂时不能处理这个请求。你可以告诉我预算、品类或使用场景。");
        }
        return answerGenerator.generateStream(request.message(), cards, compareMatrix, memory, generatedByModel::set);
    }

    private AgentStreamEvent completeTurn(TurnExecutionState state) {
        String answer = state.answerText.toString();
        if (StringUtils.hasText(state.assistantMessageId)) {
            conversationTurnAdapter.complete(state.assistantMessageId, answer);
        }
        int latencyMs = (int) Duration.ofNanos(System.nanoTime() - state.startedNanos).toMillis();
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
        private ConversationSummary memorySummary = ConversationSummary.empty();
        private CompareMatrixView compareMatrix;
        private String assistantMessageId;

        private TurnExecutionState(AgentTurnRequest request) {
            this.request = request;
            this.turnId = StringUtils.hasText(request.turnId()) ? request.turnId() : UUID.randomUUID().toString();
            this.requestId = StringUtils.hasText(request.requestId()) ? request.requestId() : this.turnId;
            this.correlationId = UUID.randomUUID().toString();
        }
    }
}
