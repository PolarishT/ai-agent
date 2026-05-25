package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.application.AgentSseEventFactory;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState;

import reactor.core.publisher.FluxSink;

public record WorkflowExecution(
        EcommerceWorkflowEngine.WorkflowRequest request,
        AgentTurnConversationState conversationState,
        WorkflowRuntimeState state,
        AgentSseEventFactory eventFactory,
        FluxSink<EcommerceWorkflowEngine.WorkflowSignal> sink
) {
    public String correlationId() {
        return request.correlationId();
    }

    public void emit(com.bytedance.ai.agent.api.AgentStreamEvent event) {
        if (sink != null && event != null) {
            sink.next(EcommerceWorkflowEngine.WorkflowSignal.event(event));
        }
    }
}
