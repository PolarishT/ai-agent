package com.bytedance.ai.agent.application;

import com.bytedance.ai.agent.answer.AgentAnswerGenerator;
import com.bytedance.ai.agent.answer.CitationExtractor;
import com.bytedance.ai.agent.api.*;
import com.bytedance.ai.agent.intent.IntentClassification;
import com.bytedance.ai.agent.intent.IntentClassifier;
import com.bytedance.ai.agent.memory.ConversationMemory;
import com.bytedance.ai.agent.memory.ConversationMemoryLoader;
import com.bytedance.ai.agent.memory.ConversationSummarizer;
import com.bytedance.ai.agent.memory.ConversationSummary;
import com.bytedance.ai.agent.persistence.AgentTurnPersistenceService;
import com.bytedance.ai.agent.persistence.AgentTurnRecord;
import com.bytedance.ai.agent.slot.MessageActionExtractor;
import com.bytedance.ai.agent.slot.MessageActionExtractor.MessageAction;
import com.bytedance.ai.agent.slot.NegationSlotExtractor;
import com.bytedance.ai.agent.slot.SlotExtractor;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.agent.tool.ToolRegistry;
import com.bytedance.ai.agent.tool.impl.*;
import com.bytedance.ai.cart.api.CartItemView;
import com.bytedance.ai.cart.api.CartView;
import com.bytedance.ai.infrastructure.config.RagConcurrencyConfiguration;
import com.bytedance.ai.order.api.OrderItemView;
import com.bytedance.ai.order.api.PlaceOrderResult;
import com.bytedance.ai.order.api.PriceChangeView;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 主流程编排，串起一次会话 turn 的完整链路：
 * <pre>
 * 幂等检查 → 写 agent_turn(RUNNING) → 写会话消息对(beginTurn) → 加载 ConversationMemory
 *   → 触发 ConversationSummarizer（每 N 轮）→ LLM 意图分类 + 主 slot 抽取
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
    /**
     * SSE {@code turn.started.model} 字段使用的版本号；prompt 改版时一并升版便于客户端识别行为差异。
     */
    private static final String MODEL_NAME = "agent-answer-v1";

    private final AgentTurnPersistenceService persistenceService;
    private final ConversationTurnAdapter conversationTurnAdapter;
    private final ConversationMemoryLoader memoryLoader;
    private final ConversationSummarizer conversationSummarizer;
    private final IntentClassifier intentClassifier;
    private final SlotExtractor slotExtractor;
    private final NegationSlotExtractor negationSlotExtractor;
    private final MessageActionExtractor messageActionExtractor;
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
            MessageActionExtractor messageActionExtractor,
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
        this.messageActionExtractor = messageActionExtractor;
        this.toolRegistry = toolRegistry;
        this.answerGenerator = answerGenerator;
        this.citationExtractor = citationExtractor;
        this.eventFactory = eventFactory;
        this.jsonCodec = jsonCodec;
        this.ragBlockingScheduler = ragBlockingScheduler;
    }

    @Override
    public Flux<AgentStreamEvent> turnStream(AgentTurnRequest request) {
        return Mono.fromCallable(() -> prepareTurn(request))
                .subscribeOn(ragBlockingScheduler)
                .flatMapMany(this::streamPreparedTurn);
    }

    private PreparedTurn prepareTurn(AgentTurnRequest request) {
        TurnExecutionState state = new TurnExecutionState(request);
        try {
            Optional<AgentTurnRecord> existing = findExistingTurn(state);
            if (existing.isPresent()) {
                return PreparedTurn.replay(replayExisting(existing.get()));
            }

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
                // 只对真正消费动作槽的 intent 调 LLM，纯检索类（RECOMMEND/FILTER/REFINE）走默认值省一次 round-trip。
                MessageAction action = needsActionExtraction(classification.intent())
                        ? messageActionExtractor.extract(request.message())
                        : MessageAction.defaults();
                executeTools(state, classification.intent(), slots, memory, action, prefixEvents, cards, toolCalls);
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

            return PreparedTurn.active(
                    state,
                    prefixEvents,
                    answerStream(
                            request,
                            classification.intent(),
                            cards,
                            state.compareMatrix,
                            state.cartAnswerText,
                            memory,
                            state.generatedByModel
                    ),
                    cards
            );
        } catch (Exception exception) {
            return PreparedTurn.failed(state, exception);
        }
    }

    private Flux<AgentStreamEvent> streamPreparedTurn(PreparedTurn prepared) {
        if (prepared.replayEvents != null) {
            return Flux.fromIterable(prepared.replayEvents);
        }
        if (prepared.failure != null) {
            return failTurn(prepared.state, prepared.failure);
        }

        TurnExecutionState state = prepared.state;
        Flux<AgentStreamEvent> answerEvents = citationExtractor.toAnswerEvents(
                prepared.answerStream.doOnNext(state.answerText::append),
                prepared.cards,
                state.correlationId,
                eventFactory
        );
        Mono<AgentStreamEvent> completed = Mono.fromCallable(() -> completeTurn(state))
                .subscribeOn(ragBlockingScheduler);
        return Flux.concat(Flux.fromIterable(prepared.prefixEvents), answerEvents, completed)
                .onErrorResume(exception -> failTurn(state, exception));
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

    /**
     * 哪些 intent 需要调 {@link MessageActionExtractor}：
     * 只有"购物车操作"和"商品对比"会消费 cartAction / quantity / priceConfirm / compareAspects；
     * 纯检索意图直接用默认值，节省一次 LLM round-trip。
     */
    private boolean needsActionExtraction(IntentType intent) {
        return intent == IntentType.CART_OP || intent == IntentType.COMPARE;
    }

    private void executeTools(
            TurnExecutionState state,
            IntentType intent,
            Slot slots,
            ConversationMemory memory,
            MessageAction action,
            List<AgentStreamEvent> prefixEvents,
            List<SpuCardView> cards,
            List<ToolCallView> toolCalls
    ) {
        // REFINE：把候选锁到上一轮 SPU 列表，相当于"在这几个里再筛"。
        List<String> restrictToSpuRefs = intent == IntentType.REFINE && memory != null
                ? memory.lastTurnSpuRefs()
                : List.of();
        List<String> lastTurnSpuRefs = memory == null ? List.of() : memory.lastTurnSpuRefs();
        for (AgentToolCallback callback : toolRegistry.plan(intent)) {
            String toolName = callback.getToolDefinition().name();
            if (intent == IntentType.CART_OP && !action.cartAction().toolName().equals(toolName)) {
                continue;
            }
            Map<String, Object> args = toolArgs(state.request, slots, restrictToSpuRefs, lastTurnSpuRefs, action);
            prefixEvents.add(eventFactory.toolCalling(state.correlationId, toolName, args));
            long started = System.nanoTime();
            switch (callback) {
                case SearchProductsToolCallback searchProductsTool -> {
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
                }
                case CompareProductsToolCallback compareProductsTool -> {
                    CompareProductsToolCallback.CompareProductsOutput output = compareProductsTool.compare(
                            new CompareProductsToolCallback.CompareProductsInput(
                                    state.request.message(),
                                    List.of(),
                                    List.of(),
                                    3,
                                    action.compareAspects()
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
                }
                case AddToCartToolCallback addToCartTool -> {
                    AddToCartToolCallback.CartToolOutput output = addToCartTool.add(new AddToCartToolCallback.CartToolInput(
                            state.request.userId(),
                            state.request.conversationId(),
                            null,
                            null,
                            lastTurnSpuRefs,
                            action.quantity(),
                            null
                    ));
                    state.cartAnswerText = cartAnswer("已加入购物车", output.cart());
                    long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    toolCalls.add(new ToolCallView(intent, toolName, args, latencyMs));
                    prefixEvents.add(eventFactory.toolResult(
                            state.correlationId,
                            output.toolName(),
                            List.of(),
                            cartFacets(output.facetsApplied(), output.cart())
                    ));
                }
                case ListCartToolCallback listCartTool -> {
                    ListCartToolCallback.CartToolOutput output = listCartTool.list(new ListCartToolCallback.CartToolInput(
                            state.request.userId(),
                            state.request.conversationId()
                    ));
                    state.cartAnswerText = cartAnswer("当前购物车", output.cart());
                    long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    toolCalls.add(new ToolCallView(intent, toolName, args, latencyMs));
                    prefixEvents.add(eventFactory.toolResult(state.correlationId, output.toolName(), List.of(), cartFacets(output.facetsApplied(), output.cart())));
                }
                case RemoveFromCartToolCallback removeFromCartTool -> {
                    RemoveFromCartToolCallback.CartToolOutput output = removeFromCartTool.remove(new RemoveFromCartToolCallback.CartToolInput(
                            state.request.userId(),
                            state.request.conversationId(),
                            null,
                            null,
                            null,
                            lastTurnSpuRefs
                    ));
                    state.cartAnswerText = cartAnswer("已从购物车移除", output.cart());
                    long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    toolCalls.add(new ToolCallView(intent, toolName, args, latencyMs));
                    prefixEvents.add(eventFactory.toolResult(state.correlationId, output.toolName(), List.of(), cartFacets(output.facetsApplied(), output.cart())));
                }
                case UpdateCartQtyToolCallback updateCartQtyTool -> {
                    UpdateCartQtyToolCallback.CartToolOutput output = updateCartQtyTool.update(new UpdateCartQtyToolCallback.CartToolInput(
                            state.request.userId(),
                            state.request.conversationId(),
                            null,
                            null,
                            null,
                            lastTurnSpuRefs,
                            action.quantity()
                    ));
                    state.cartAnswerText = cartAnswer("已更新购物车数量", output.cart());
                    long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    toolCalls.add(new ToolCallView(intent, toolName, args, latencyMs));
                    prefixEvents.add(eventFactory.toolResult(state.correlationId, output.toolName(), List.of(), cartFacets(output.facetsApplied(), output.cart())));
                }
                case PlaceOrderToolCallback placeOrderTool -> {
                    PlaceOrderToolCallback.PlaceOrderOutput output = placeOrderTool.place(new PlaceOrderToolCallback.PlaceOrderInput(
                            state.request.userId(),
                            state.request.conversationId(),
                            Map.of(),
                            action.priceChangeConfirmed()
                    ));
                    state.cartAnswerText = orderAnswer(output.result());
                    long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    toolCalls.add(new ToolCallView(intent, toolName, args, latencyMs));
                    prefixEvents.add(eventFactory.toolResult(
                            state.correlationId,
                            output.toolName(),
                            List.of(),
                            orderFacets(output.facetsApplied(), output.result())
                    ));
                }
                default -> {
                    callback.call(jsonCodec.write(args));
                    long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    toolCalls.add(new ToolCallView(intent, toolName, args, latencyMs));
                }
            }
        }
    }

    private Map<String, Object> toolArgs(
            AgentTurnRequest request,
            Slot slots,
            List<String> restrictToSpuRefs,
            List<String> lastTurnSpuRefs,
            MessageAction action
    ) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", request.message());
        args.put("userId", request.userId());
        args.put("conversationId", request.conversationId());
        args.put("slots", slots);
        args.put("topK", 10);
        args.put("includeChunkTypes", List.of());
        args.put("restrictToSpuRefs", restrictToSpuRefs == null ? List.of() : restrictToSpuRefs);
        args.put("lastTurnSpuRefs", lastTurnSpuRefs == null ? List.of() : lastTurnSpuRefs);
        args.put("cartAction", action.cartAction().name());
        args.put("quantity", action.quantity());
        args.put("priceChangeConfirmed", action.priceChangeConfirmed());
        args.put("compareAspects", action.compareAspects());
        return args;
    }

    private Map<String, Object> cartFacets(Map<String, Object> base, CartView cart) {
        Map<String, Object> facets = new LinkedHashMap<>(base == null ? Map.of() : base);
        facets.put("cart", cart);
        return facets;
    }

    private Map<String, Object> orderFacets(Map<String, Object> base, PlaceOrderResult result) {
        Map<String, Object> facets = new LinkedHashMap<>(base == null ? Map.of() : base);
        facets.put("placed", result.placed());
        facets.put("confirmationRequired", result.confirmationRequired());
        facets.put("code", result.code());
        if (result.order() != null) {
            facets.put("order", result.order());
        }
        if (!result.priceChanges().isEmpty()) {
            facets.put("priceChanges", result.priceChanges());
        }
        return facets;
    }

    private String orderAnswer(PlaceOrderResult result) {
        if (result.confirmationRequired()) {
            StringBuilder builder = new StringBuilder("商品价格已变化，请确认后再下单：");
            for (PriceChangeView change : result.priceChanges()) {
                builder.append("\n- ")
                        .append(change.title())
                        .append("：购物车价 ")
                        .append(change.cartUnitPrice())
                        .append("，当前价 ")
                        .append(change.currentUnitPrice());
            }
            builder.append("\n如果接受新价格，请回复“确认下单”。");
            return builder.toString();
        }
        if (result.order() == null) {
            return result.message();
        }
        StringBuilder builder = new StringBuilder("订单已提交，订单号 ")
                .append(result.order().orderId())
                .append("。共 ")
                .append(result.order().itemCount())
                .append(" 件，合计 ")
                .append(result.order().subtotalAmount())
                .append(" ")
                .append(result.order().currency())
                .append("。");
        int index = 1;
        for (OrderItemView item : result.order().items()) {
            builder.append("\n")
                    .append(index++)
                    .append(". ")
                    .append(item.title())
                    .append(" x ")
                    .append(item.quantity());
        }
        return builder.toString();
    }

    private String cartAnswer(String prefix, CartView cart) {
        if (cart == null || cart.items().isEmpty()) {
            return prefix + "。当前购物车为空。";
        }
        StringBuilder builder = new StringBuilder(prefix)
                .append("。当前购物车共 ")
                .append(cart.itemCount())
                .append(" 件，合计 ")
                .append(cart.subtotalAmount())
                .append(" ")
                .append(cart.currency())
                .append("。");
        int index = 1;
        for (CartItemView item : cart.items()) {
            builder.append("\n")
                    .append(index++)
                    .append(". ")
                    .append(item.title())
                    .append(" x ")
                    .append(item.quantity());
            if (item.lineAmount() != null) {
                builder.append("，小计 ").append(item.lineAmount());
            }
        }
        return builder.toString();
    }

    private Flux<String> answerStream(
            AgentTurnRequest request,
            IntentType intent,
            List<SpuCardView> cards,
            CompareMatrixView compareMatrix,
            String cartAnswerText,
            ConversationMemory memory,
            AtomicBoolean generatedByModel
    ) {
        if (intent == IntentType.OUT_OF_SCOPE) {
            generatedByModel.set(false);
            return Flux.just("我只能帮助你挑选和比较商品，暂时不能处理这个请求。你可以告诉我预算、品类或使用场景。");
        }
        if (intent == IntentType.CART_OP && StringUtils.hasText(cartAnswerText)) {
            generatedByModel.set(false);
            return Flux.just(cartAnswerText);
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
        return Mono.fromCallable(() -> failTurnBlocking(state, exception))
                .subscribeOn(ragBlockingScheduler)
                .flux();
    }

    private AgentStreamEvent failTurnBlocking(TurnExecutionState state, Throwable exception) {
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
        return eventFactory.turnError(state.correlationId, "AGENT_TURN_ERROR", message, false);
    }

    private record PreparedTurn(TurnExecutionState state, List<AgentStreamEvent> prefixEvents,
                                Flux<String> answerStream, List<SpuCardView> cards, List<AgentStreamEvent> replayEvents,
                                Throwable failure) {
            private PreparedTurn(
                    TurnExecutionState state,
                    List<AgentStreamEvent> prefixEvents,
                    Flux<String> answerStream,
                    List<SpuCardView> cards,
                    List<AgentStreamEvent> replayEvents,
                    Throwable failure
            ) {
                this.state = state;
                this.prefixEvents = prefixEvents == null ? List.of() : List.copyOf(prefixEvents);
                this.answerStream = answerStream;
                this.cards = cards == null ? List.of() : List.copyOf(cards);
                this.replayEvents = replayEvents == null ? null : List.copyOf(replayEvents);
                this.failure = failure;
            }

            private static PreparedTurn active(
                    TurnExecutionState state,
                    List<AgentStreamEvent> prefixEvents,
                    Flux<String> answerStream,
                    List<SpuCardView> cards
            ) {
                return new PreparedTurn(state, prefixEvents, answerStream, cards, null, null);
            }

            private static PreparedTurn replay(List<AgentStreamEvent> replayEvents) {
                return new PreparedTurn(null, List.of(), Flux.empty(), List.of(), replayEvents, null);
            }

            private static PreparedTurn failed(TurnExecutionState state, Throwable failure) {
                return new PreparedTurn(state, List.of(), Flux.empty(), List.of(), null, failure);
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
        private CompareMatrixView compareMatrix;
        private String cartAnswerText;
        private String assistantMessageId;

        private TurnExecutionState(AgentTurnRequest request) {
            this.request = request;
            this.turnId = StringUtils.hasText(request.turnId()) ? request.turnId() : UUID.randomUUID().toString();
            this.requestId = StringUtils.hasText(request.requestId()) ? request.requestId() : this.turnId;
            this.correlationId = UUID.randomUUID().toString();
        }
    }
}
