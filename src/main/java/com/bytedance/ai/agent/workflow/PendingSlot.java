package com.bytedance.ai.agent.workflow;

import java.util.List;

public record PendingSlot(
        List<String> missingSlots,
        String sourceNode,
        String resumeNode
) {
    public PendingSlot {
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }
}
