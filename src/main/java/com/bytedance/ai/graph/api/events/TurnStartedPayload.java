package com.bytedance.ai.graph.api.events;

/**
 * 导购 API流式事件载荷：Turn Started Payload。
 */
public record TurnStartedPayload(
        String turnId,
        String conversationId,
        String model
) {
}
