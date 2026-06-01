package com.bytedance.ai.graph.api.events;

/**
 * 导购 API流式事件载荷：Workflow Node Started Payload。
 */
public record WorkflowNodeStartedPayload(String nodeName) {
}
