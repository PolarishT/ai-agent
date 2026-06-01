package com.bytedance.ai.graph.api.events;

import java.util.Map;

/**
 * 导购 API流式事件载荷：Workflow Node Completed Payload。
 */
public record WorkflowNodeCompletedPayload(
        String nodeName,
        Long latencyMs,
        Map<String, Object> summary
) {
    public WorkflowNodeCompletedPayload {
        summary = summary == null ? Map.of() : Map.copyOf(summary);
    }
}
