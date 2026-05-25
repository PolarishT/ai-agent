package com.bytedance.ai.agent.workflow;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用人工确认节点：在执行真正动作之前发出确认请求，并把流程暂停为
 * {@link WorkflowStatus#WAITING_CONFIRMATION}，等用户下一轮回复"确认 / 下单"。
 */
@Component
public class HumanConfirmNodeExecutor implements WorkflowNodeExecutor {

    @Override
    public boolean supports(NodeType type) {
        return type == NodeType.HUMAN_CONFIRM;
    }

    @Override
    public Map<String, Object> execute(WorkflowExecution execution, WorkflowDefinition.WorkflowNodeDefinition node) {
        WorkflowRuntimeState state = execution.state();
        PendingConfirmation pending = state.pendingConfirmation();
        if (pending != null && pending.confirmed()) {
            return Map.of("status", state.status().name(), "confirmed", true);
        }
        Map<String, Object> orderDraft = new LinkedHashMap<>();
        if (state.pendingSelection() != null) {
            orderDraft.put("externalRef", state.pendingSelection().selectedExternalRef());
            orderDraft.put("skuId", state.pendingSelection().selectedSkuId());
        }
        orderDraft.put("intent", state.intent().name());
        state.pendingConfirmation(new PendingConfirmation(
                "ORDER_CREATE", node.id(), resumeNode(node), orderDraft, false));
        state.status(WorkflowStatus.WAITING_CONFIRMATION);
        state.currentNode(node.id());
        state.answerText("请确认是否继续下单，回复\"确认\"即可。");
        return Map.of("status", state.status().name(), "waitingFor", "confirmation");
    }

    private String resumeNode(WorkflowDefinition.WorkflowNodeDefinition node) {
        // 默认回到 order_create，可由 workflow JSON 通过 edge whenStatus=WAITING_CONFIRMATION 调整。
        return "order_create";
    }
}
