package com.bytedance.ai.agent.application;

import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.api.ToolCallView;
import com.bytedance.ai.agent.memory.ConversationSummary;
import com.bytedance.ai.agent.workflow.EcommerceWorkflowEngine;
import com.bytedance.ai.agent.workflow.WorkflowStatus;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class AgentWorkflowService {

    private final EcommerceWorkflowEngine ecommerceWorkflowEngine;

    public AgentWorkflowService(EcommerceWorkflowEngine ecommerceWorkflowEngine) {
        this.ecommerceWorkflowEngine = ecommerceWorkflowEngine;
    }

    public Flux<WorkflowSignal> run(WorkflowRequest request) {
        return ecommerceWorkflowEngine.run(new EcommerceWorkflowEngine.WorkflowRequest(
                        request.request(),
                        request.turnId(),
                        request.correlationId(),
                        request.conversationState()
                ))
                .map(signal -> new WorkflowSignal(
                        signal.event(),
                        signal.result() == null ? null : adapt(signal.result())
                ));
    }

    private WorkflowResult adapt(EcommerceWorkflowEngine.WorkflowResult result) {
        return new WorkflowResult(
                result.intent(),
                result.slots(),
                result.toolCalls(),
                result.cards(),
                result.compareMatrix(),
                result.answerText(),
                result.generatedByModel(),
                result.memorySummary(),
                result.status()
        );
    }

    public record WorkflowRequest(
            AgentTurnRequest request,
            String turnId,
            String correlationId,
            AgentTurnConversationState conversationState
    ) {
    }

    public record WorkflowResult(
            IntentType intent,
            Slot slots,
            List<ToolCallView> toolCalls,
            List<SpuCardView> cards,
            CompareMatrixView compareMatrix,
            String answerText,
            boolean generatedByModel,
            ConversationSummary memorySummary,
            WorkflowStatus status
    ) {
        public WorkflowResult {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            cards = cards == null ? List.of() : List.copyOf(cards);
            answerText = answerText == null ? "" : answerText;
        }
    }

    public record WorkflowSignal(AgentStreamEvent event, WorkflowResult result) {
    }
}
