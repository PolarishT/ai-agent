package com.bytedance.ai.agent.application;

import com.bytedance.ai.agent.answer.AgentAnswerGenerator;
import com.bytedance.ai.agent.answer.CitationExtractor;
import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.memory.ConversationMemory;
import com.bytedance.ai.agent.memory.ConversationMemoryLoader;
import com.bytedance.ai.agent.memory.ConversationSummarizer;
import com.bytedance.ai.agent.memory.ConversationSummary;
import com.bytedance.ai.agent.persistence.AgentTurnPersistenceService;
import com.bytedance.ai.agent.persistence.AgentTurnRecord;
import com.bytedance.ai.agent.persistence.AgentTurnRepository;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.agent.tool.ToolRegistry;
import com.bytedance.ai.agent.workflow.EcommerceWorkflowEngine;
import com.bytedance.ai.agent.workflow.EdgeResolver;
import com.bytedance.ai.agent.workflow.FinalAndControlNodeExecutor;
import com.bytedance.ai.agent.workflow.InMemoryWorkflowStateStore;
import com.bytedance.ai.agent.workflow.InventoryCheckNodeExecutor;
import com.bytedance.ai.agent.workflow.NodeExecutorRegistry;
import com.bytedance.ai.agent.workflow.OrderCreateNodeExecutor;
import com.bytedance.ai.agent.workflow.PendingSelection;
import com.bytedance.ai.agent.workflow.RagExecutor;
import com.bytedance.ai.agent.workflow.ResumeInputParser;
import com.bytedance.ai.agent.workflow.ToolExecutor;
import com.bytedance.ai.agent.workflow.WorkflowDefinition;
import com.bytedance.ai.agent.workflow.WorkflowDefinitionLoader;
import com.bytedance.ai.agent.workflow.WorkflowExpressionResolver;
import com.bytedance.ai.agent.workflow.WorkflowNodeExecutor;
import com.bytedance.ai.agent.workflow.WorkflowRuntimeState;
import com.bytedance.ai.agent.workflow.WorkflowStateStore;
import com.bytedance.ai.agent.workflow.WorkflowStatus;
import com.bytedance.ai.retrieval.spi.AgentConversationSpi;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentTurnService 与 {@link EcommerceWorkflowEngine} 的真实集成测试。
 *
 * <p>用程序化构造的 workflow 取代生产 ecommerce-guide-v1.json，让流程进入
 * {@code inventory_check → order_create → final_answer}，避免依赖 LLM 路由。
 * 仍然走完整 AgentTurnService 链路（begin / SSE / persistence / complete or fail）。
 */
class AgentTurnServiceTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());

    @Test
    void happyPathTurnBeginsEmitsEventsCompletesAndPersists() {
        InMemoryAgentTurnRepository repository = new InMemoryAgentTurnRepository();
        StubToolCallback checkStock = new StubToolCallback("check_stock", input -> Map.of(
                "toolName", "check_stock", "stock", 7, "available", true));
        StubToolCallback placeOrder = new StubToolCallback("place_order", input -> Map.of(
                "toolName", "place_order", "result", Map.of("orderId", "o1", "code", "OK")));
        TestWiring wiring = wire(repository, List.of(checkStock, placeOrder));

        // 预置 state：模拟前一轮已经搜出 1 个商品，currentNode=inventory_check（单候选自动锁定）。
        WorkflowRuntimeState seeded = new WorkflowRuntimeState();
        seeded.intent(IntentType.CART_OP);
        seeded.cards(List.of(card(1L, "ext-1")));
        seeded.currentNode("inventory_check");
        seeded.status(WorkflowStatus.RUNNING);
        wiring.stateStore.save("c-happy", seeded);
        // 单候选场景下 inventory_check 不会暂停 — 直接进入 order_create，但 order_create 仍要确认。
        // 给出已确认的 pendingConfirmation 跳过 WAITING_CONFIRMATION，让 happy path 走到 place_order。
        seeded.pendingConfirmation(new com.bytedance.ai.agent.workflow.PendingConfirmation(
                "ORDER_CREATE", "order_create", "order_create", Map.of(), true));
        seeded.pendingSelection(new PendingSelection(
                "spu", "inventory_check", "inventory_check",
                seeded.cards(), "ext-1", 1L));

        List<AgentStreamEvent> events = wiring.service.turnStream(new AgentTurnRequest(
                "u-happy", "c-happy", "确认下单", "turn-happy", null, null, null
        )).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::event).contains("turn.started", "turn.completed");
        assertThat(checkStock.calls()).as("happy path 应该调用 check_stock").hasSize(1);
        assertThat(placeOrder.calls()).as("已确认时应该调用 place_order").hasSize(1);

        AgentTurnRecord record = repository.findByTurnId("turn-happy").orElseThrow();
        assertThat(record.status()).isEqualTo("SUCCEEDED");
        assertThat(record.completedAt()).isNotNull();
    }

    @Test
    void unconfirmedOrderPausesAndDoesNotInvokePlaceOrder() {
        InMemoryAgentTurnRepository repository = new InMemoryAgentTurnRepository();
        StubToolCallback checkStock = new StubToolCallback("check_stock", input -> Map.of(
                "toolName", "check_stock", "stock", 3, "available", true));
        StubToolCallback placeOrder = new StubToolCallback("place_order", input -> Map.of(
                "toolName", "place_order", "result", Map.of("orderId", "o2", "code", "OK")));
        TestWiring wiring = wire(repository, List.of(checkStock, placeOrder));

        WorkflowRuntimeState seeded = new WorkflowRuntimeState();
        seeded.intent(IntentType.CART_OP);
        seeded.cards(List.of(card(1L, "ext-1")));
        seeded.currentNode("inventory_check");
        seeded.status(WorkflowStatus.RUNNING);
        seeded.pendingSelection(new PendingSelection(
                "spu", "inventory_check", "inventory_check",
                seeded.cards(), "ext-1", 1L));
        wiring.stateStore.save("c-noconfirm", seeded);

        wiring.service.turnStream(new AgentTurnRequest(
                "u-noconfirm", "c-noconfirm", "看看库存", "turn-noconfirm", null, null, null
        )).collectList().block();

        assertThat(checkStock.calls()).hasSize(1);
        assertThat(placeOrder.calls()).as("未确认时绝不调用 place_order").isEmpty();
        WorkflowRuntimeState after = wiring.stateStore.restore("c-noconfirm");
        assertThat(after.status()).isEqualTo(WorkflowStatus.WAITING_CONFIRMATION);
        // turn 本身仍然 SUCCEEDED —— WAITING_* 只是 workflow 状态，turn 完成度独立。
        AgentTurnRecord record = repository.findByTurnId("turn-noconfirm").orElseThrow();
        assertThat(record.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void toolFailureMarksTurnFailed() {
        InMemoryAgentTurnRepository repository = new InMemoryAgentTurnRepository();
        StubToolCallback failingCheck = new StubToolCallback("check_stock", input -> {
            throw new IllegalStateException("库存服务连接超时");
        });
        StubToolCallback placeOrder = new StubToolCallback("place_order", input -> Map.of(
                "toolName", "place_order", "result", Map.of()));
        TestWiring wiring = wire(repository, List.of(failingCheck, placeOrder));

        WorkflowRuntimeState seeded = new WorkflowRuntimeState();
        seeded.intent(IntentType.CART_OP);
        seeded.cards(List.of(card(1L, "ext-1")));
        seeded.currentNode("inventory_check");
        seeded.pendingSelection(new PendingSelection(
                "spu", "inventory_check", "inventory_check",
                seeded.cards(), "ext-1", 1L));
        seeded.status(WorkflowStatus.RUNNING);
        wiring.stateStore.save("c-fail", seeded);

        wiring.service.turnStream(new AgentTurnRequest(
                "u-fail", "c-fail", "查库存", "turn-fail", null, null, null
        )).collectList().blockOptional();

        assertThat(placeOrder.calls()).isEmpty();
        AgentTurnRecord record = repository.findByTurnId("turn-fail").orElseThrow();
        assertThat(record.status()).isEqualTo("FAILED");
        assertThat(record.errorMessage()).contains("库存");
    }

    private TestWiring wire(InMemoryAgentTurnRepository repository, List<AgentToolCallback> tools) {
        AgentTurnPersistenceService persistenceService = new AgentTurnPersistenceService(repository, jsonCodec);
        ConversationTurnAdapter conversationTurnAdapter = new ConversationTurnAdapter(new StubConversationSpi());
        ToolRegistry toolRegistry = new ToolRegistry(tools);
        WorkflowExpressionResolver resolver = new WorkflowExpressionResolver();
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry, jsonCodec, resolver);

        AgentAnswerGenerator answerGenerator = new AgentAnswerGenerator(noChatModel(),
                new ClassPathResource("prompts/agent-answer-v1.txt"));
        RagExecutor ragExecutor = new RagExecutor();
        FinalAndControlNodeExecutor finalAnswer = new FinalAndControlNodeExecutor(answerGenerator, ragExecutor);
        InventoryCheckNodeExecutor inventory = new InventoryCheckNodeExecutor(toolExecutor, jsonCodec);
        OrderCreateNodeExecutor order = new OrderCreateNodeExecutor(toolExecutor, jsonCodec);
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.<WorkflowNodeExecutor>of(
                inventory, order, finalAnswer));

        WorkflowDefinitionLoader definitionLoader = new TestDefinitionLoader(testDefinition());
        InMemoryWorkflowStateStore stateStore = new InMemoryWorkflowStateStore();
        ConversationMemoryLoader memoryLoader = new StubMemoryLoader();
        ConversationSummarizer summarizer = new StubSummarizer();
        AgentSseEventFactory eventFactory = new AgentSseEventFactory();

        EcommerceWorkflowEngine engine = new EcommerceWorkflowEngine(
                definitionLoader, stateStore, registry, new EdgeResolver(),
                memoryLoader, summarizer, eventFactory, new ResumeInputParser()
        );
        AgentWorkflowService workflowService = new AgentWorkflowService(engine);
        AgentTurnService service = new AgentTurnService(
                persistenceService,
                conversationTurnAdapter,
                new CitationExtractor(),
                eventFactory,
                workflowService,
                Schedulers.immediate()
        );
        return new TestWiring(service, stateStore);
    }

    private WorkflowDefinition testDefinition() {
        WorkflowDefinition.ToolBinding checkStockBinding = new WorkflowDefinition.ToolBinding(
                "check_stock",
                Map.of("type", "object"),
                Map.of("type", "object"),
                new LinkedHashMap<>(Map.of(
                        "externalRef", "$.pending.selection.selectedExternalRef",
                        "spuId", "$.pending.selection.selectedSkuId"
                )),
                Map.of()
        );
        WorkflowDefinition.ToolBinding placeOrderBinding = new WorkflowDefinition.ToolBinding(
                "place_order",
                Map.of("type", "object"),
                Map.of("type", "object"),
                new LinkedHashMap<>(Map.of(
                        "userId", "$.request.userId",
                        "conversationId", "$.request.conversationId",
                        "externalRef", "$.pending.selection.selectedExternalRef"
                )),
                Map.of()
        );
        return new WorkflowDefinition(
                "ecommerce-guide-test", "inventory_check",
                List.of(
                        new WorkflowDefinition.WorkflowNodeDefinition(
                                "inventory_check", "inventory_check", null, checkStockBinding,
                                Map.of(), Map.of(), "WAITING_SELECTION", "inventory_check"),
                        new WorkflowDefinition.WorkflowNodeDefinition(
                                "order_create", "order_create", null, placeOrderBinding,
                                Map.of(), Map.of(), "WAITING_CONFIRMATION", "order_create"),
                        new WorkflowDefinition.WorkflowNodeDefinition(
                                "final_answer", "final_answer", null, null,
                                Map.of(), Map.of(), null, null),
                        new WorkflowDefinition.WorkflowNodeDefinition(
                                "end", "end", null, null, Map.of(), Map.of(), null, null)
                ),
                List.of(
                        new WorkflowDefinition.WorkflowEdgeDefinition("inventory_check", "order_create", null, null, true),
                        new WorkflowDefinition.WorkflowEdgeDefinition("order_create", "final_answer", null, null, true),
                        new WorkflowDefinition.WorkflowEdgeDefinition("final_answer", "end", null, null, true)
                )
        );
    }

    private SpuCardView card(Long spuId, String externalRef) {
        return new SpuCardView(spuId, externalRef, "title-" + spuId, "brand", null,
                BigDecimal.ZERO, BigDecimal.ZERO, 5, 0.0, List.of(), List.of(), "#" + spuId);
    }

    private static ObjectProvider<ChatModel> noChatModel() {
        return new ObjectProvider<>() {
            @Override public ChatModel getObject(Object... args) throws BeansException { return null; }
            @Override public ChatModel getIfAvailable() throws BeansException { return null; }
            @Override public ChatModel getIfUnique() throws BeansException { return null; }
            @Override public ChatModel getObject() throws BeansException { return null; }
        };
    }

    private record TestWiring(AgentTurnService service, InMemoryWorkflowStateStore stateStore) {
    }

    static final class TestDefinitionLoader extends WorkflowDefinitionLoader {
        private final WorkflowDefinition definition;

        TestDefinitionLoader(WorkflowDefinition definition) {
            super(null, null);
            this.definition = definition;
        }

        @Override
        public WorkflowDefinition loadDefault() {
            return definition;
        }

        @Override
        public WorkflowDefinition load(String workflowId) {
            return definition;
        }
    }

    static final class StubMemoryLoader extends ConversationMemoryLoader {
        StubMemoryLoader() {
            super(null, null);
        }

        @Override
        public ConversationMemory load(String conversationId, List<com.bytedance.ai.retrieval.spi.AgentTurnConversationState.ConversationTurn> history) {
            return ConversationMemory.empty();
        }
    }

    static final class StubSummarizer extends ConversationSummarizer {
        StubSummarizer() {
            super(noChatModel());
        }

        @Override
        public ConversationSummary summarize(
                List<com.bytedance.ai.retrieval.spi.AgentTurnConversationState.ConversationTurn> history,
                java.util.Optional<String> previousSummary,
                Integer previousMessageCount
        ) {
            return ConversationSummary.empty();
        }
    }

    static final class StubToolCallback implements AgentToolCallback {
        private final String name;
        private final java.util.function.Function<Map<String, Object>, Map<String, Object>> handler;
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());

        StubToolCallback(String name, java.util.function.Function<Map<String, Object>, Map<String, Object>> handler) {
            this.name = name;
            this.handler = handler;
        }

        @Override
        public Set<IntentType> handles() {
            return Set.of();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public String call(String toolInput) {
            Map<String, Object> input = jsonCodec.readMap(toolInput);
            calls.add(input);
            return jsonCodec.write(handler.apply(input));
        }

        List<Map<String, Object>> calls() {
            return calls;
        }
    }

    private static class StubConversationSpi implements AgentConversationSpi {
        @Override
        public AgentTurnConversationState beginTurn(String userId, String conversationId, String userMessage, String correlationId) {
            return new AgentTurnConversationState(1L, "user-msg-1", "assistant-msg-1", List.of());
        }

        @Override
        public void completeTurn(String assistantMessageId, String answerText) {
        }

        @Override
        public void failTurn(String assistantMessageId, String errorCode, String errorMessage) {
        }
    }

    private class InMemoryAgentTurnRepository implements AgentTurnRepository {
        private final Map<String, AgentTurnRecord> records = new LinkedHashMap<>();

        @Override
        public void createRunning(String turnId, String correlationId, String userId, String conversationId, String requestId, String userMessage) {
            records.put(turnId, new AgentTurnRecord(
                    (long) (records.size() + 1), turnId, correlationId, userId, conversationId, requestId,
                    null, null, "RUNNING", userMessage, null, null, null,
                    "{}", "[]", "[]", null, null, null, null, null,
                    null, null, null, null, null, OffsetDateTime.now(), null
            ));
        }

        @Override
        public Optional<AgentTurnRecord> findByTurnId(String turnId) {
            return Optional.ofNullable(records.get(turnId));
        }

        @Override
        public Optional<AgentTurnRecord> findByRequestId(String userId, String conversationId, String requestId) {
            return records.values().stream()
                    .filter(r -> r.userId().equals(userId)
                            && r.conversationId().equals(conversationId)
                            && requestId.equals(r.requestId()))
                    .findFirst();
        }

        @Override
        public List<AgentTurnRecord> findRecentByConversationId(String conversationId, int limit) {
            return new ArrayList<>(records.values());
        }

        @Override
        public Optional<AgentTurnRecord> findLatestMemorySummary(String conversationId) {
            return Optional.empty();
        }

        @Override
        public void attachConversationMessages(String turnId, String userMessageId, String assistantMessageId) {
            AgentTurnRecord r = records.get(turnId);
            if (r == null) return;
            records.put(turnId, copy(r, userMessageId, assistantMessageId, r.status(), r.intent(), r.intentSource(),
                    r.intentConfidence(), r.slotsJson(), r.toolsCalled(), r.cardsEmitted(), r.generatedByModel(),
                    r.answerText(), r.memorySummary(), r.memorySummaryMessageCount(), r.memorySummaryModel(),
                    r.latencyMs(), r.errorCode(), r.errorMessage(), r.completedAt()));
        }

        @Override
        public void recordIntent(String turnId, String intent, String source, Double confidence, String slotsJson) {
            AgentTurnRecord r = records.get(turnId);
            if (r == null) return;
            records.put(turnId, copy(r, r.userMessageId(), r.assistantMessageId(), r.status(), intent, source,
                    confidence, slotsJson, r.toolsCalled(), r.cardsEmitted(), r.generatedByModel(), r.answerText(),
                    r.memorySummary(), r.memorySummaryMessageCount(), r.memorySummaryModel(),
                    r.latencyMs(), r.errorCode(), r.errorMessage(), r.completedAt()));
        }

        @Override
        public void recordToolState(String turnId, String toolsCalledJson, String cardsEmittedJson) {
            AgentTurnRecord r = records.get(turnId);
            if (r == null) return;
            records.put(turnId, copy(r, r.userMessageId(), r.assistantMessageId(), r.status(), r.intent(),
                    r.intentSource(), r.intentConfidence(), r.slotsJson(), toolsCalledJson, cardsEmittedJson,
                    r.generatedByModel(), r.answerText(), r.memorySummary(), r.memorySummaryMessageCount(),
                    r.memorySummaryModel(), r.latencyMs(), r.errorCode(), r.errorMessage(), r.completedAt()));
        }

        @Override
        public void markSucceeded(String turnId, String answerText, Boolean generatedByModel, Integer tokensIn,
                                  Integer tokensOut, Integer latencyMs, String memorySummary,
                                  Integer memorySummaryMessageCount, String memorySummaryModel) {
            AgentTurnRecord r = records.get(turnId);
            if (r == null) return;
            records.put(turnId, copy(r, r.userMessageId(), r.assistantMessageId(), "SUCCEEDED", r.intent(),
                    r.intentSource(), r.intentConfidence(), r.slotsJson(), r.toolsCalled(), r.cardsEmitted(),
                    generatedByModel, answerText, memorySummary, memorySummaryMessageCount, memorySummaryModel,
                    latencyMs, null, null, OffsetDateTime.now()));
        }

        @Override
        public void markFailed(String turnId, String errorCode, String errorMessage, Integer latencyMs) {
            AgentTurnRecord r = records.get(turnId);
            if (r == null) return;
            records.put(turnId, copy(r, r.userMessageId(), r.assistantMessageId(), "FAILED", r.intent(),
                    r.intentSource(), r.intentConfidence(), r.slotsJson(), r.toolsCalled(), r.cardsEmitted(),
                    r.generatedByModel(), r.answerText(), r.memorySummary(), r.memorySummaryMessageCount(),
                    r.memorySummaryModel(), latencyMs, errorCode, errorMessage, OffsetDateTime.now()));
        }

        private AgentTurnRecord copy(AgentTurnRecord r, String userMessageId, String assistantMessageId, String status,
                                     String intent, String intentSource, Double intentConfidence, String slotsJson,
                                     String toolsCalled, String cardsEmitted, Boolean generatedByModel, String answerText,
                                     String memorySummary, Integer memorySummaryMessageCount, String memorySummaryModel,
                                     Integer latencyMs, String errorCode, String errorMessage, OffsetDateTime completedAt) {
            return new AgentTurnRecord(
                    r.id(), r.turnId(), r.correlationId(), r.userId(), r.conversationId(), r.requestId(),
                    userMessageId, assistantMessageId, status, r.userMessage(), intent, intentSource,
                    intentConfidence, slotsJson, toolsCalled, cardsEmitted, generatedByModel, answerText,
                    memorySummary, memorySummaryMessageCount, memorySummaryModel,
                    r.tokensIn(), r.tokensOut(), latencyMs, errorCode, errorMessage, r.startedAt(), completedAt
            );
        }
    }
}
