package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.answer.AgentAnswerGenerator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FinalAndControlNodeExecutor implements WorkflowNodeExecutor {

    private final AgentAnswerGenerator answerGenerator;
    private final RagExecutor ragExecutor;

    public FinalAndControlNodeExecutor(AgentAnswerGenerator answerGenerator, RagExecutor ragExecutor) {
        this.answerGenerator = answerGenerator;
        this.ragExecutor = ragExecutor;
    }

    @Override
    public boolean supports(NodeType type) {
        return type == NodeType.FINAL_ANSWER
                || type == NodeType.RAG_QA
                || type == NodeType.END;
    }

    @Override
    public Map<String, Object> execute(WorkflowExecution execution, WorkflowDefinition.WorkflowNodeDefinition node) {
        WorkflowRuntimeState state = execution.state();
        switch (node.nodeType()) {
            case RAG_QA -> state.answerText(ragExecutor.answer(execution.request().request().message(), state));
            case FINAL_ANSWER -> {
                if (state.answerText() == null || state.answerText().isBlank() || !state.cards().isEmpty()) {
                    List<String> deltas = answerGenerator.generateStream(
                                    execution.request().request().message(),
                                    state.cards(),
                                    state.compareMatrix(),
                                    state.memory(),
                                    state.generatedByModel()::set
                            )
                            .collectList()
                            .blockOptional()
                            .orElse(List.of());
                    state.answerText(String.join("", deltas));
                }
                state.status(WorkflowStatus.END);
                state.currentNode(null);
            }
            case END -> {
                state.status(WorkflowStatus.END);
                state.currentNode(null);
            }
            default -> {
            }
        }
        return Map.of("status", state.status().name(), "answerLength", state.answerText().length());
    }
}
