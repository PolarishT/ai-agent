package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.api.ToolCallView;
import com.bytedance.ai.agent.application.AgentSseEventFactory;
import com.bytedance.ai.shared.support.RagJsonCodec;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryCheckNodeExecutorTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
    private final ToolExecutor toolExecutor = mock(ToolExecutor.class);
    private final InventoryCheckNodeExecutor executor = new InventoryCheckNodeExecutor(toolExecutor, jsonCodec);

    @Test
    void multipleCandidatesPauseAsWaitingSelectionWithoutToolCall() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.cards(List.of(card(1L, "ext-1"), card(2L, "ext-2")));
        WorkflowExecution execution = newExecution(state);

        WorkflowDefinition.WorkflowNodeDefinition node = new WorkflowDefinition.WorkflowNodeDefinition(
                "inventory_check", "inventory_check", null, stubToolBinding(), Map.of(), Map.of(),
                "WAITING_SELECTION", "inventory_check");

        executor.execute(execution, node);

        assertThat(state.status()).isEqualTo(WorkflowStatus.WAITING_SELECTION);
        assertThat(state.pendingSelection()).isNotNull();
        assertThat(state.pendingSelection().candidates()).hasSize(2);
        assertThat(state.currentNode()).isEqualTo("inventory_check");
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void resolvedSelectionInvokesTool() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.cards(List.of(card(1L, "ext-1"), card(2L, "ext-2")));
        state.pendingSelection(new PendingSelection(
                "spu", "inventory_check", "inventory_check",
                state.cards(), "ext-2", 2L));
        WorkflowExecution execution = newExecution(state);

        WorkflowDefinition.WorkflowNodeDefinition node = new WorkflowDefinition.WorkflowNodeDefinition(
                "inventory_check", "inventory_check", null, stubToolBinding(), Map.of(), Map.of(),
                "WAITING_SELECTION", "inventory_check");

        ToolCallView call = new ToolCallView(state.intent(), "check_stock", Map.of("externalRef", "ext-2"), 1L);
        when(toolExecutor.execute(any(), any(), any())).thenReturn(new ToolExecutor.ToolExecutionResult(
                "check_stock", Map.of("externalRef", "ext-2"),
                Map.of("toolName", "check_stock", "stock", 5, "available", true), call));

        executor.execute(execution, node);

        verify(toolExecutor).execute(any(), any(), any());
        assertThat(state.toolCalls()).hasSize(1);
        assertThat(state.lastToolResults()).containsKey("check_stock");
    }

    private SpuCardView card(Long spuId, String externalRef) {
        return new SpuCardView(spuId, externalRef, "title-" + spuId, "brand", null,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, 0.0, List.of(), List.of(), "#" + spuId);
    }

    private WorkflowDefinition.ToolBinding stubToolBinding() {
        return new WorkflowDefinition.ToolBinding("check_stock", Map.of(), Map.of(), Map.of(), Map.of());
    }

    private WorkflowExecution newExecution(WorkflowRuntimeState state) {
        AgentTurnRequest req = new AgentTurnRequest("u", "c", "msg", "t", null, null, List.of());
        EcommerceWorkflowEngine.WorkflowRequest workflowRequest =
                new EcommerceWorkflowEngine.WorkflowRequest(req, "t", "corr", null);
        return new WorkflowExecution(workflowRequest, null, state, new AgentSseEventFactory(), null);
    }
}
