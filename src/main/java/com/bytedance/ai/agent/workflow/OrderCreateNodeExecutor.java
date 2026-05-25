package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下单节点：执行 place_order 前 **强制** 校验 confirmation 已通过。
 * 否则即便上游节点直连过来，也只暂停为 {@link WorkflowStatus#WAITING_CONFIRMATION}，
 * 绝不调用底层 tool。这是计划文档明确点名的安全网。
 */
@Component
public class OrderCreateNodeExecutor implements WorkflowNodeExecutor {

    private final ToolExecutor toolExecutor;
    private final RagJsonCodec jsonCodec;

    public OrderCreateNodeExecutor(ToolExecutor toolExecutor, RagJsonCodec jsonCodec) {
        this.toolExecutor = toolExecutor;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public boolean supports(NodeType type) {
        return type == NodeType.ORDER_CREATE;
    }

    @Override
    public Map<String, Object> execute(WorkflowExecution execution, WorkflowDefinition.WorkflowNodeDefinition node) {
        WorkflowRuntimeState state = execution.state();
        if (!isConfirmed(state)) {
            Map<String, Object> orderDraft = new LinkedHashMap<>();
            if (state.pendingSelection() != null) {
                orderDraft.put("externalRef", state.pendingSelection().selectedExternalRef());
                orderDraft.put("skuId", state.pendingSelection().selectedSkuId());
            }
            state.pendingConfirmation(new PendingConfirmation(
                    "ORDER_CREATE", node.id(), node.id(), orderDraft, false));
            state.status(WorkflowStatus.WAITING_CONFIRMATION);
            state.currentNode(node.id());
            state.answerText("下单前需要确认，请回复\"确认\"以继续。");
            return Map.of("status", state.status().name(), "waitingFor", "confirmation", "guard", "ORDER_CREATE");
        }
        ToolExecutor.ToolExecutionResult result = toolExecutor.execute(node.tool(), state, execution.request());
        state.toolCalls().add(result.toolCall());
        state.lastToolResults().put(result.toolName(), result.output());
        state.answerText(jsonCodec.write(result.output()));
        execution.emit(execution.eventFactory().toolResult(execution.correlationId(), result.toolName(), state.cards(), Map.of()));
        state.clearPending();
        state.currentNode(null);
        return Map.of("toolName", result.toolName(), "status", state.status().name());
    }

    private boolean isConfirmed(WorkflowRuntimeState state) {
        PendingConfirmation pending = state.pendingConfirmation();
        return pending != null && pending.confirmed() && "ORDER_CREATE".equals(pending.type());
    }
}
