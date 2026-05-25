package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.application.AgentSseEventFactory;
import com.bytedance.ai.agent.memory.ConversationMemoryLoader;
import com.bytedance.ai.agent.memory.ConversationSummarizer;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.agent.tool.ToolRegistry;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 端到端验证暂停 / 恢复闭环：通过 {@link EcommerceWorkflowEngine#run(EcommerceWorkflowEngine.WorkflowRequest)}
 * 跑 3 轮，断言 {@code place_order} 仅在 {@code WAITING_CONFIRMATION} 被用户确认后才被调用。
 *
 * <p>用一份程序化构造的测试 workflow 取代真实的 ecommerce-guide-v1.json，让 inventory_check
 * 默认走向 order_create —— 真实生产 workflow 还需要 intent_router 路由，这里不依赖 LLM。
 */
class WorkflowResumeE2ETests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());

    @Test
    void candidatePauseSelectionConfirmAndOrderEndToEnd() {
        StubToolCallback checkStock = new StubToolCallback("check_stock", input -> Map.of(
                "toolName", "check_stock",
                "stock", 5,
                "available", true,
                "externalRef", input.get("externalRef")
        ));
        StubToolCallback placeOrder = new StubToolCallback("place_order", input -> Map.of(
                "toolName", "place_order",
                "result", Map.of("orderId", "order-1", "code", "OK")
        ));
        ToolRegistry toolRegistry = new ToolRegistry(List.of(checkStock, placeOrder));
        WorkflowExpressionResolver expressionResolver = new WorkflowExpressionResolver();
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry, jsonCodec, expressionResolver);

        InventoryCheckNodeExecutor inventory = new InventoryCheckNodeExecutor(toolExecutor, jsonCodec);
        OrderCreateNodeExecutor order = new OrderCreateNodeExecutor(toolExecutor, jsonCodec);
        FinalAnswerStub finalAnswer = new FinalAnswerStub();
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(inventory, order, finalAnswer));

        WorkflowDefinitionLoader definitionLoader = mock(WorkflowDefinitionLoader.class);
        when(definitionLoader.loadDefault()).thenReturn(testDefinition());

        InMemoryWorkflowStateStore stateStore = new InMemoryWorkflowStateStore();
        ConversationMemoryLoader memoryLoader = mock(ConversationMemoryLoader.class);
        when(memoryLoader.load(any(), any())).thenReturn(com.bytedance.ai.agent.memory.ConversationMemory.empty());
        ConversationSummarizer summarizer = mock(ConversationSummarizer.class);
        when(summarizer.summarize(any(), any(), any()))
                .thenReturn(com.bytedance.ai.agent.memory.ConversationSummary.empty());

        EcommerceWorkflowEngine engine = new EcommerceWorkflowEngine(
                definitionLoader, stateStore, registry, new EdgeResolver(),
                memoryLoader, summarizer, new AgentSseEventFactory(), new ResumeInputParser()
        );

        String conv = "conv-e2e";

        // ===== Turn 1 (prime): "推荐国产中油皮洗面奶" — 模拟搜索/排序已经把 3 个候选放到 state，下一站是 inventory_check =====
        WorkflowRuntimeState seeded = new WorkflowRuntimeState();
        seeded.intent(IntentType.CART_OP);
        seeded.cards(threeCards());
        seeded.currentNode("inventory_check");
        seeded.status(WorkflowStatus.RUNNING);
        stateStore.save(conv, seeded);

        runTurn(engine, conv, "查第二个的库存");
        WorkflowRuntimeState afterTurn1 = stateStore.restore(conv);
        assertThat(afterTurn1.status()).isEqualTo(WorkflowStatus.WAITING_SELECTION);
        assertThat(afterTurn1.pendingSelection()).isNotNull();
        assertThat(afterTurn1.pendingSelection().candidates()).hasSize(3);
        assertThat(checkStock.calls()).as("未选择前不得查库存").isEmpty();
        assertThat(placeOrder.calls()).as("未确认前不得下单").isEmpty();

        // ===== Turn 2: 用户说 "第二个" =====
        runTurn(engine, conv, "第二个");
        WorkflowRuntimeState afterTurn2 = stateStore.restore(conv);
        assertThat(checkStock.calls()).as("第二个被选中后应触发 check_stock").hasSize(1);
        assertThat(checkStock.lastInput().get("externalRef")).isEqualTo("ext-2");
        assertThat(afterTurn2.status()).isEqualTo(WorkflowStatus.WAITING_CONFIRMATION);
        assertThat(afterTurn2.pendingConfirmation()).isNotNull();
        assertThat(afterTurn2.pendingConfirmation().confirmed()).isFalse();
        assertThat(placeOrder.calls()).as("WAITING_CONFIRMATION 时仍不得下单").isEmpty();

        // ===== Turn 3: 用户说 "确认下单" =====
        runTurn(engine, conv, "确认下单");
        WorkflowRuntimeState afterTurn3 = stateStore.restore(conv);
        assertThat(placeOrder.calls()).as("确认后应调用 place_order").hasSize(1);
        Map<String, Object> orderInput = placeOrder.lastInput();
        assertThat(orderInput.get("userId")).isEqualTo("u-e2e");
        assertThat(orderInput.get("conversationId")).isEqualTo(conv);
        assertThat(orderInput.get("externalRef")).isEqualTo("ext-2");
        assertThat(afterTurn3).as("END 后 store 已 clear").isNotNull();
        assertThat(afterTurn3.status()).isEqualTo(WorkflowStatus.RUNNING); // restore() 返回新 state
    }

    private void runTurn(EcommerceWorkflowEngine engine, String conv, String message) {
        AgentTurnRequest req = new AgentTurnRequest("u-e2e", conv, message, "turn-" + message.hashCode(), null, null, List.of());
        AgentTurnConversationState convState = new AgentTurnConversationState(1L, "u-msg", "a-msg", List.of());
        EcommerceWorkflowEngine.WorkflowRequest wr = new EcommerceWorkflowEngine.WorkflowRequest(req, req.turnId(), "corr", convState);
        List<AgentStreamEvent> events = engine.run(wr)
                .mapNotNull(EcommerceWorkflowEngine.WorkflowSignal::event)
                .collectList()
                .block();
        assertThat(events).as("each turn should emit at least one workflow event").isNotNull();
    }

    private WorkflowDefinition testDefinition() {
        WorkflowDefinition.ToolBinding checkStockBinding = new WorkflowDefinition.ToolBinding(
                "check_stock", Map.of("type", "object"), Map.of("type", "object"),
                new LinkedHashMap<>(Map.of(
                        "externalRef", "$.pending.selection.selectedExternalRef",
                        "spuId", "$.pending.selection.selectedSkuId"
                )),
                Map.of()
        );
        WorkflowDefinition.ToolBinding placeOrderBinding = new WorkflowDefinition.ToolBinding(
                "place_order", Map.of("type", "object"), Map.of("type", "object"),
                new LinkedHashMap<>(Map.of(
                        "userId", "$.request.userId",
                        "conversationId", "$.request.conversationId",
                        "externalRef", "$.pending.selection.selectedExternalRef"
                )),
                Map.of()
        );
        WorkflowDefinition.WorkflowNodeDefinition inventory = new WorkflowDefinition.WorkflowNodeDefinition(
                "inventory_check", "inventory_check", null, checkStockBinding, Map.of(), Map.of(),
                "WAITING_SELECTION", "inventory_check");
        WorkflowDefinition.WorkflowNodeDefinition order = new WorkflowDefinition.WorkflowNodeDefinition(
                "order_create", "order_create", null, placeOrderBinding, Map.of(), Map.of(),
                "WAITING_CONFIRMATION", "order_create");
        WorkflowDefinition.WorkflowNodeDefinition finalAnswer = new WorkflowDefinition.WorkflowNodeDefinition(
                "final_answer", "final_answer", null, null, Map.of(), Map.of(), null, null);
        WorkflowDefinition.WorkflowNodeDefinition end = new WorkflowDefinition.WorkflowNodeDefinition(
                "end", "end", null, null, Map.of(), Map.of(), null, null);
        List<WorkflowDefinition.WorkflowEdgeDefinition> edges = List.of(
                new WorkflowDefinition.WorkflowEdgeDefinition("inventory_check", "order_create", null, null, true),
                new WorkflowDefinition.WorkflowEdgeDefinition("order_create", "final_answer", null, null, true),
                new WorkflowDefinition.WorkflowEdgeDefinition("final_answer", "end", null, null, true)
        );
        return new WorkflowDefinition("ecommerce-guide-e2e-test", "inventory_check",
                List.of(inventory, order, finalAnswer, end), edges);
    }

    private List<SpuCardView> threeCards() {
        return List.of(
                card(1L, "ext-1"),
                card(2L, "ext-2"),
                card(3L, "ext-3")
        );
    }

    private SpuCardView card(Long spuId, String externalRef) {
        return new SpuCardView(spuId, externalRef, "洗面奶-" + spuId, "国产", null,
                BigDecimal.valueOf(80), BigDecimal.valueOf(120), 10, 0.5, List.of(), List.of(), "#" + spuId);
    }

    /** 节点 final_answer / end 的轻量实现，避免拉起 AgentAnswerGenerator。 */
    static final class FinalAnswerStub implements WorkflowNodeExecutor {
        @Override
        public boolean supports(NodeType type) {
            return type == NodeType.FINAL_ANSWER || type == NodeType.END;
        }

        @Override
        public Map<String, Object> execute(WorkflowExecution execution, WorkflowDefinition.WorkflowNodeDefinition node) {
            execution.state().status(WorkflowStatus.END);
            execution.state().currentNode(null);
            return Map.of("status", execution.state().status().name());
        }
    }

    /** ToolCallback stub，记录调用并返回固定 output。 */
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

        Map<String, Object> lastInput() {
            return calls.getLast();
        }
    }

}
