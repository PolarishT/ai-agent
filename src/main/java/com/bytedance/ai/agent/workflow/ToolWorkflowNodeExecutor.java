package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ToolWorkflowNodeExecutor implements WorkflowNodeExecutor {

    private final ToolExecutor toolExecutor;
    private final RagJsonCodec jsonCodec;

    public ToolWorkflowNodeExecutor(ToolExecutor toolExecutor, RagJsonCodec jsonCodec) {
        this.toolExecutor = toolExecutor;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public boolean supports(NodeType type) {
        return type == NodeType.PRODUCT_SEARCH
                || type == NodeType.PRODUCT_COMPARE;
    }

    @Override
    public Map<String, Object> execute(WorkflowExecution execution, WorkflowDefinition.WorkflowNodeDefinition node) {
        ToolExecutor.ToolExecutionResult result = toolExecutor.execute(node.tool(), execution.state(), execution.request());
        execution.emit(execution.eventFactory().toolCalling(execution.correlationId(), result.toolName(), result.input()));
        execution.state().toolCalls().add(result.toolCall());
        execution.state().lastToolResults().put(result.toolName(), result.output());
        normalizeOutput(node.nodeType(), execution, result);
        emitToolResult(node.nodeType(), execution, result);
        return Map.of("toolName", result.toolName(), "status", execution.state().status().name());
    }

    private void normalizeOutput(NodeType type, WorkflowExecution execution, ToolExecutor.ToolExecutionResult result) {
        if (result.output().containsKey("cards")) {
            execution.state().cards(readList(result.output().get("cards"), SpuCardView.class));
        }
        if (result.output().containsKey("compareMatrix")) {
            execution.state().compareMatrix(jsonCodec.read(jsonCodec.write(result.output().get("compareMatrix")), CompareMatrixView.class));
        }
    }

    private void emitToolResult(NodeType type, WorkflowExecution execution, ToolExecutor.ToolExecutionResult result) {
        List<SpuCardView> cards = execution.state().cards();
        if (type == NodeType.PRODUCT_COMPARE && execution.state().compareMatrix() != null) {
            execution.emit(execution.eventFactory().toolResult(
                    execution.correlationId(),
                    result.toolName(),
                    cards,
                    Map.of(),
                    execution.state().compareMatrix()
            ));
            return;
        }
        execution.emit(execution.eventFactory().toolResult(execution.correlationId(), result.toolName(), cards, Map.of()));
    }

    private <T> List<T> readList(Object value, Class<T> type) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> jsonCodec.read(jsonCodec.write(item), type))
                .toList();
    }
}
