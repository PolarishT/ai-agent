package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.api.ToolCallView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 序列化友好的 workflow 运行时快照。
 *
 * <p>memory / memorySummary / generatedByModel 不入库 —— 它们每轮由 {@code prepareMemory()} 重新拉。
 */
public record WorkflowRuntimeStateSnapshot(
        WorkflowStatus status,
        IntentType intent,
        Slot slots,
        String targetNode,
        String currentNode,
        String action,
        boolean needsReset,
        List<ToolCallView> toolCalls,
        Map<String, Object> lastToolResults,
        List<SpuCardView> cards,
        CompareMatrixView compareMatrix,
        String answerText,
        PendingSelection pendingSelection,
        PendingConfirmation pendingConfirmation,
        PendingSlot pendingSlot
) {

    public WorkflowRuntimeStateSnapshot {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        lastToolResults = lastToolResults == null ? Map.of() : Map.copyOf(lastToolResults);
        cards = cards == null ? List.of() : List.copyOf(cards);
    }

    public static WorkflowRuntimeStateSnapshot from(WorkflowRuntimeState state) {
        return new WorkflowRuntimeStateSnapshot(
                state.status(),
                state.intent(),
                state.slots(),
                state.targetNode(),
                state.currentNode(),
                state.action(),
                state.needsReset(),
                List.copyOf(state.toolCalls()),
                new LinkedHashMap<>(state.lastToolResults()),
                state.cards(),
                state.compareMatrix(),
                state.answerText(),
                state.pendingSelection(),
                state.pendingConfirmation(),
                state.pendingSlot()
        );
    }

    public WorkflowRuntimeState toRuntimeState() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.status(status);
        state.intent(intent);
        state.slots(slots);
        state.targetNode(targetNode);
        state.currentNode(currentNode);
        state.action(action);
        state.needsReset(needsReset);
        state.toolCalls().addAll(toolCalls);
        state.lastToolResults().putAll(lastToolResults);
        state.cards(new ArrayList<>(cards));
        state.compareMatrix(compareMatrix);
        state.answerText(answerText);
        state.pendingSelection(pendingSelection);
        state.pendingConfirmation(pendingConfirmation);
        state.pendingSlot(pendingSlot);
        return state;
    }
}
