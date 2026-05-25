package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.application.AgentSseEventFactory;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.Map;

public class WorkflowEventPublisher {

    private final String correlationId;
    private final AgentSseEventFactory eventFactory;
    private final FluxSink<?> sink;

    public WorkflowEventPublisher(String correlationId, AgentSseEventFactory eventFactory, FluxSink<?> sink) {
        this.correlationId = correlationId;
        this.eventFactory = eventFactory;
        this.sink = sink;
    }

    public AgentStreamEvent nodeStarted(String nodeName) {
        return eventFactory.workflowNodeStarted(correlationId, nodeName);
    }

    public AgentStreamEvent nodeCompleted(String nodeName, long latencyMs, Map<String, Object> summary) {
        return eventFactory.workflowNodeCompleted(correlationId, nodeName, latencyMs, summary);
    }

    public AgentStreamEvent toolCalling(String toolName, Object args) {
        return eventFactory.toolCalling(correlationId, toolName, args);
    }

    public AgentStreamEvent toolResult(String toolName, List<SpuCardView> cards, Map<String, Object> facets) {
        return eventFactory.toolResult(correlationId, toolName, cards, facets);
    }

    public AgentStreamEvent toolResult(String toolName, List<SpuCardView> cards, Map<String, Object> facets, CompareMatrixView compareMatrix) {
        return eventFactory.toolResult(correlationId, toolName, cards, facets, compareMatrix);
    }
}
