package com.bytedance.ai.agent.workflow;

import java.util.Map;

public record PendingConfirmation(
        String type,
        String sourceNode,
        String resumeNode,
        Map<String, Object> orderDraft,
        boolean confirmed
) {
    public PendingConfirmation {
        orderDraft = orderDraft == null ? Map.of() : Map.copyOf(orderDraft);
    }

    public PendingConfirmation withConfirmed(boolean value) {
        return new PendingConfirmation(type, sourceNode, resumeNode, orderDraft, value);
    }
}
