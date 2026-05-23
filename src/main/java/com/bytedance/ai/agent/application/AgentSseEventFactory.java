package com.bytedance.ai.agent.application;

import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.api.events.AnswerDeltaPayload;
import com.bytedance.ai.agent.api.events.CitationPayload;
import com.bytedance.ai.agent.api.events.IntentDetectedPayload;
import com.bytedance.ai.agent.api.events.NoticePayload;
import com.bytedance.ai.agent.api.events.ToolCallingPayload;
import com.bytedance.ai.agent.api.events.ToolResultPayload;
import com.bytedance.ai.agent.api.events.TurnCompletedPayload;
import com.bytedance.ai.agent.api.events.TurnErrorPayload;
import com.bytedance.ai.agent.api.events.TurnStartedPayload;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AgentSseEventFactory {

    public AgentStreamEvent turnStarted(String correlationId, String turnId, String conversationId, String model) {
        return event("turn.started", correlationId, new TurnStartedPayload(turnId, conversationId, model));
    }

    public AgentStreamEvent intentDetected(
            String correlationId,
            IntentType intent,
            double confidence,
            String source,
            Slot slots
    ) {
        return event("intent.detected", correlationId, new IntentDetectedPayload(intent, confidence, source, slots));
    }

    public AgentStreamEvent toolCalling(String correlationId, String toolName, Object args) {
        return event("tool.calling", correlationId, new ToolCallingPayload(toolName, args));
    }

    public AgentStreamEvent toolResult(
            String correlationId,
            String toolName,
            List<SpuCardView> cards,
            Map<String, Object> facetsApplied
    ) {
        return event("tool.result", correlationId, new ToolResultPayload(toolName, cards, facetsApplied));
    }

    public AgentStreamEvent answerDelta(String correlationId, String text) {
        return event("answer.delta", correlationId, new AnswerDeltaPayload(text));
    }

    public AgentStreamEvent citation(String correlationId, String refId, Long spuId, Long chunkId) {
        return event("citation", correlationId, new CitationPayload(refId, spuId, chunkId));
    }

    public AgentStreamEvent notice(String correlationId, String code, String message, String severity) {
        return event("notice", correlationId, new NoticePayload(code, message, severity));
    }

    public AgentStreamEvent turnCompleted(
            String correlationId,
            String turnId,
            Integer latencyMs,
            Integer tokensIn,
            Integer tokensOut,
            boolean generatedByModel
    ) {
        return event("turn.completed", correlationId,
                new TurnCompletedPayload(turnId, latencyMs, tokensIn, tokensOut, generatedByModel));
    }

    public AgentStreamEvent turnError(String correlationId, String code, String message, boolean recoverable) {
        return event("turn.error", correlationId, new TurnErrorPayload(code, message, recoverable));
    }

    private AgentStreamEvent event(String name, String correlationId, Object payload) {
        return new AgentStreamEvent(UUID.randomUUID().toString(), name, correlationId, payload);
    }
}
