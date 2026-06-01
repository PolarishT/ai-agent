package com.bytedance.ai.graph.api.events;

/**
 * 导购 API流式事件载荷：Turn Error Payload。
 */
public record TurnErrorPayload(
        String code,
        String message,
        boolean recoverable
) {
}
