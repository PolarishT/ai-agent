package com.bytedance.ai.graph.api;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * 导购 Graph 执行请求，携带用户输入、会话信息和前端上下文。
 */
public record GuideGraphRequest(
        String userId,
        String conversationId,
        String message,
        String runId,
        String requestId,
        String imageRef,
        String correlationId,
        GuideGraphIntent initialIntent,
        List<?> history
) {
    public GuideGraphRequest {
        runId = StringUtils.hasText(runId) ? runId : UUID.randomUUID().toString();
        requestId = StringUtils.hasText(requestId) ? requestId : UUID.randomUUID().toString();
        correlationId = StringUtils.hasText(correlationId) ? correlationId : requestId;
        history = history == null ? List.of() : List.copyOf(history);
    }

    public static GuideGraphRequest from(AgentTurnRequest request) {
        return from(request, null);
    }

    /**
     * @deprecated Controller must not provide intent. Keep this only for tests and legacy callers
     * that intentionally seed graph state.
     */
    @Deprecated(forRemoval = false)
    public static GuideGraphRequest from(AgentTurnRequest request, GuideGraphIntent initialIntent) {
        return new GuideGraphRequest(
                request.userId(),
                request.conversationId(),
                request.message(),
                request.turnId(),
                request.requestId(),
                request.imageRef(),
                null,
                initialIntent,
                request.history()
        );
    }
}
