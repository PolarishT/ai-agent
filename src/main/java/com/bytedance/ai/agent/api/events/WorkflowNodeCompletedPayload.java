package com.bytedance.ai.agent.api.events;

import java.util.Map;

public record WorkflowNodeCompletedPayload(
        String nodeName,
        Long latencyMs,
        Map<String, Object> summary
) {
    public WorkflowNodeCompletedPayload {
        summary = summary == null ? Map.of() : Map.copyOf(summary);
    }
}
