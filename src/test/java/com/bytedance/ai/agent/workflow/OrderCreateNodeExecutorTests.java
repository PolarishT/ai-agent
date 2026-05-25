package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.ToolCallView;
import com.bytedance.ai.agent.application.AgentSseEventFactory;
import com.bytedance.ai.shared.support.RagJsonCodec;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCreateNodeExecutorTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
    private final ToolExecutor toolExecutor = mock(ToolExecutor.class);
    private final OrderCreateNodeExecutor executor = new OrderCreateNodeExecutor(toolExecutor, jsonCodec);

    @Test
    void unconfirmedRequestPausesAsWaitingConfirmationAndDoesNotCallTool() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        WorkflowExecution execution = newExecution(state);
        WorkflowDefinition.WorkflowNodeDefinition node = node();

        executor.execute(execution, node);

        assertThat(state.status()).isEqualTo(WorkflowStatus.WAITING_CONFIRMATION);
        assertThat(state.pendingConfirmation()).isNotNull();
        assertThat(state.pendingConfirmation().confirmed()).isFalse();
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void confirmedRequestInvokesPlaceOrderTool() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.pendingConfirmation(new PendingConfirmation(
                "ORDER_CREATE", "human_confirm", "order_create", Map.of(), true));
        WorkflowExecution execution = newExecution(state);
        WorkflowDefinition.WorkflowNodeDefinition node = node();

        ToolCallView call = new ToolCallView(state.intent(), "place_order", Map.of(), 1L);
        when(toolExecutor.execute(any(), any(), any())).thenReturn(new ToolExecutor.ToolExecutionResult(
                "place_order", Map.of(), Map.of("toolName", "place_order", "result", Map.of("orderId", "o1")), call));

        executor.execute(execution, node);

        verify(toolExecutor).execute(any(), any(), any());
        assertThat(state.toolCalls()).hasSize(1);
        assertThat(state.pendingConfirmation()).isNull();
        assertThat(state.currentNode()).isNull();
    }

    @Test
    void confirmationFromWrongTypeStillGuards() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        // confirmed=true but type mismatches → must still pause.
        state.pendingConfirmation(new PendingConfirmation(
                "REFUND", "human_confirm", "order_create", Map.of(), true));
        WorkflowExecution execution = newExecution(state);

        executor.execute(execution, node());

        assertThat(state.status()).isEqualTo(WorkflowStatus.WAITING_CONFIRMATION);
        verify(toolExecutor, never()).execute(any(), any(), any());
    }

    private WorkflowDefinition.WorkflowNodeDefinition node() {
        return new WorkflowDefinition.WorkflowNodeDefinition(
                "order_create", "order_create", null,
                new WorkflowDefinition.ToolBinding("place_order", Map.of(), Map.of(), Map.of(), Map.of()),
                Map.of(), Map.of(), "WAITING_CONFIRMATION", "order_create");
    }

    private WorkflowExecution newExecution(WorkflowRuntimeState state) {
        AgentTurnRequest req = new AgentTurnRequest("u", "c", "msg", "t", null, null, List.of());
        EcommerceWorkflowEngine.WorkflowRequest workflowRequest =
                new EcommerceWorkflowEngine.WorkflowRequest(req, "t", "corr", null);
        return new WorkflowExecution(workflowRequest, null, state, new AgentSseEventFactory(), null);
    }
}
