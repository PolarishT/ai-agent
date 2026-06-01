package com.bytedance.ai.graph.api;

import java.time.Instant;

/**
 * 导购 Graph 单次回合的最终摘要。
 */
public record GuideGraphFinalSummary(
        String runId,
        String requestId,
        String correlationId,
        String conversationId,
        String userId,
        GuideGraphIntent intent,
        String targetWorkflow,
        NodeRunStatus status,
        String finalNode,
        String errorCode,
        String errorMessage,
        Instant completedAt
) {

    public static GuideGraphFinalSummary success(
            GuideGraphRequest request,
            GuideGraphIntent intent,
            String targetWorkflow,
            NodeRunStatus status,
            String finalNode
    ) {
        return new GuideGraphFinalSummary(
                request.runId(),
                request.requestId(),
                request.correlationId(),
                request.conversationId(),
                request.userId(),
                intent,
                targetWorkflow,
                status,
                finalNode,
                null,
                null,
                Instant.now()
        );
    }

    public static GuideGraphFinalSummary failed(
            GuideGraphRequest request,
            GuideGraphIntent intent,
            String targetWorkflow,
            String finalNode,
            String errorCode,
            String errorMessage
    ) {
        return new GuideGraphFinalSummary(
                request.runId(),
                request.requestId(),
                request.correlationId(),
                request.conversationId(),
                request.userId(),
                intent,
                targetWorkflow,
                NodeRunStatus.FAILED,
                finalNode,
                errorCode,
                errorMessage,
                Instant.now()
        );
    }
}