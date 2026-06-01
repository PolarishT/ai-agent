package com.bytedance.ai.graph.orchestration;

import com.bytedance.ai.graph.api.AgentStreamEvent;
import com.bytedance.ai.graph.api.events.AnswerCompletedPayload;
import com.bytedance.ai.graph.api.events.AnswerDeltaPayload;
import com.bytedance.ai.graph.api.events.TurnErrorPayload;
import com.bytedance.ai.graph.api.events.TurnStartedPayload;
import com.bytedance.ai.graph.api.events.WorkflowNodeCompletedPayload;
import com.bytedance.ai.graph.api.events.WorkflowNodeStartedPayload;
import com.bytedance.ai.graph.api.events.WorkflowStartedPayload;
import com.bytedance.ai.graph.api.AgentStreamEventType;
import com.bytedance.ai.graph.api.GuideGraphFinalSummary;
import com.bytedance.ai.graph.api.GuideGraphRequest;

import java.util.Map;
import java.util.UUID;

public final class GuideGraphStreamEvents {

    private GuideGraphStreamEvents() {
    }

    public static AgentStreamEvent turnStarted(GuideGraphRequest request) {
        return of(
                AgentStreamEventType.TURN_STARTED,
                request.correlationId(),
                new TurnStartedPayload(request.runId(), request.conversationId(), "guide-state-graph-v1")
        );
    }

    public static AgentStreamEvent workflowStarted(String correlationId, String workflowName, String intent) {
        return of(
                AgentStreamEventType.WORKFLOW_STARTED,
                correlationId,
                new WorkflowStartedPayload(workflowName, intent)
        );
    }

    public static AgentStreamEvent nodeStarted(String correlationId, String nodeName) {
        return of(
                AgentStreamEventType.NODE_STARTED,
                correlationId,
                new WorkflowNodeStartedPayload(nodeName)
        );
    }

    public static AgentStreamEvent nodeCompleted(
            String correlationId,
            String nodeName,
            long latencyMs,
            Map<String, Object> summary
    ) {
        return of(
                AgentStreamEventType.NODE_COMPLETED,
                correlationId,
                new WorkflowNodeCompletedPayload(nodeName, latencyMs, summary)
        );
    }

    public static AgentStreamEvent nodeFailed(
            String correlationId,
            String nodeName,
            long latencyMs,
            Map<String, Object> summary
    ) {
        return of(
                AgentStreamEventType.NODE_FAILED,
                correlationId,
                new WorkflowNodeCompletedPayload(nodeName, latencyMs, summary)
        );
    }

    public static AgentStreamEvent answerDelta(
            String correlationId,
            String text
    ) {
        return of(
                AgentStreamEventType.ANSWER_DELTA,
                correlationId,
                new AnswerDeltaPayload(text)
        );
    }

    public static AgentStreamEvent answerCompleted(
            String correlationId,
            String messageId
    ) {
        return of(
                AgentStreamEventType.ANSWER_COMPLETED,
                correlationId,
                new AnswerCompletedPayload(messageId)
        );
    }

    public static AgentStreamEvent turnCompleted(
            String correlationId,
            GuideGraphFinalSummary summary
    ) {
        return of(
                AgentStreamEventType.TURN_COMPLETED,
                correlationId,
                summary
        );
    }

    public static AgentStreamEvent turnError(
            String correlationId,
            String errorCode,
            String errorMessage,
            boolean recoverable
    ) {
        return of(
                AgentStreamEventType.TURN_ERROR,
                correlationId,
                new TurnErrorPayload(errorCode, errorMessage, recoverable)
        );
    }

    private static AgentStreamEvent of(
            AgentStreamEventType type,
            String correlationId,
            Object data
    ) {
        return new AgentStreamEvent(
                UUID.randomUUID().toString(),
                type.eventName(),
                correlationId,
                data
        );
    }
}
