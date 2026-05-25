package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 库存查询节点：先确认"用户想查哪个商品"。
 *
 * <ul>
 *     <li>如果 pendingSelection 已解析（用户明确选过）→ 直接调用 check_stock。</li>
 *     <li>如果候选只有一个 → 自动锁定，调用 check_stock。</li>
 *     <li>否则 → 暂停为 {@link WorkflowStatus#WAITING_SELECTION}，等用户选择后由
 *         {@link ResumeInputParser} 回灌 selectedExternalRef，再 resume 到本节点。</li>
 * </ul>
 */
@Component
public class InventoryCheckNodeExecutor implements WorkflowNodeExecutor {

    private final ToolExecutor toolExecutor;
    private final RagJsonCodec jsonCodec;

    public InventoryCheckNodeExecutor(ToolExecutor toolExecutor, RagJsonCodec jsonCodec) {
        this.toolExecutor = toolExecutor;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public boolean supports(NodeType type) {
        return type == NodeType.INVENTORY_CHECK;
    }

    @Override
    public Map<String, Object> execute(WorkflowExecution execution, WorkflowDefinition.WorkflowNodeDefinition node) {
        WorkflowRuntimeState state = execution.state();
        if (!ensureSelection(state, node)) {
            execution.emit(execution.eventFactory().workflowNodeCompleted(
                    execution.correlationId(), node.id(), 0L,
                    Map.of("status", state.status().name(), "waitingFor", "selection")));
            return Map.of("status", state.status().name(), "waitingFor", "selection");
        }
        ToolExecutor.ToolExecutionResult result = toolExecutor.execute(node.tool(), state, execution.request());
        state.toolCalls().add(result.toolCall());
        state.lastToolResults().put(result.toolName(), result.output());
        state.answerText(jsonCodec.write(result.output()));
        execution.emit(execution.eventFactory().toolResult(execution.correlationId(), result.toolName(), state.cards(), Map.of()));
        return Map.of("toolName", result.toolName(), "status", state.status().name());
    }

    private boolean ensureSelection(WorkflowRuntimeState state, WorkflowDefinition.WorkflowNodeDefinition node) {
        PendingSelection pending = state.pendingSelection();
        if (pending != null && pending.resolved()) {
            return true;
        }
        List<SpuCardView> candidates = state.cards();
        if (candidates.size() == 1) {
            SpuCardView only = candidates.getFirst();
            state.pendingSelection(new PendingSelection(
                    "spu", node.id(), node.id(),
                    candidates, only.externalRef(), only.spuId()));
            return true;
        }
        state.status(WorkflowStatus.WAITING_SELECTION);
        state.currentNode(node.id());
        state.pendingSelection(new PendingSelection(
                "spu", node.id(), node.id(),
                candidates, null, null));
        state.answerText(buildSelectionPrompt(candidates));
        return false;
    }

    private String buildSelectionPrompt(List<SpuCardView> candidates) {
        if (candidates.isEmpty()) {
            return "当前没有可查询库存的商品，请先告诉我商品名称或重新搜索。";
        }
        StringBuilder builder = new StringBuilder("请问您想查哪一款的库存？\n");
        for (int i = 0; i < candidates.size(); i++) {
            SpuCardView card = candidates.get(i);
            builder.append(i + 1).append(". ");
            if (StringUtils.hasText(card.title())) {
                builder.append(card.title());
            } else if (StringUtils.hasText(card.externalRef())) {
                builder.append(card.externalRef());
            }
            builder.append('\n');
        }
        builder.append("可以回复\"第二个\"或具体名称。");
        return builder.toString();
    }
}
