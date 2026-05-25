package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.ToolCallView;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.agent.tool.ToolRegistry;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final RagJsonCodec jsonCodec;
    private final WorkflowExpressionResolver expressionResolver;

    public ToolExecutor(ToolRegistry toolRegistry, RagJsonCodec jsonCodec, WorkflowExpressionResolver expressionResolver) {
        this.toolRegistry = toolRegistry;
        this.jsonCodec = jsonCodec;
        this.expressionResolver = expressionResolver;
    }

    public ToolExecutionResult execute(
            WorkflowDefinition.ToolBinding binding,
            WorkflowRuntimeState state,
            EcommerceWorkflowEngine.WorkflowRequest request
    ) {
        if (binding == null || binding.toolName() == null || binding.toolName().isBlank()) {
            throw new IllegalArgumentException("workflow tool binding 缺少 toolName");
        }
        AgentToolCallback callback = toolRegistry.findByName(binding.toolName())
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow tool: " + binding.toolName()));
        Map<String, Object> input = buildInput(binding, state, request);
        validateRequired(binding.inputSchema(), input, "tool input");
        long started = System.nanoTime();
        String rawOutput = callback.call(jsonCodec.write(input));
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Map<String, Object> output = jsonCodec.readMap(rawOutput);
        validateRequired(binding.outputSchema(), output, "tool output");
        ToolCallView toolCall = new ToolCallView(state.intent(), binding.toolName(), input, latencyMs);
        return new ToolExecutionResult(binding.toolName(), input, output, toolCall);
    }

    private Map<String, Object> buildInput(
            WorkflowDefinition.ToolBinding binding,
            WorkflowRuntimeState state,
            EcommerceWorkflowEngine.WorkflowRequest request
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : binding.inputMapping().entrySet()) {
            input.put(entry.getKey(), expressionResolver.resolve(entry.getValue(), state, request));
        }
        return input;
    }

    @SuppressWarnings("unchecked")
    private void validateRequired(Map<String, Object> schema, Map<String, Object> value, String label) {
        Object required = schema == null ? null : schema.get("required");
        if (!(required instanceof List<?> requiredFields)) {
            return;
        }
        for (Object field : requiredFields) {
            String key = String.valueOf(field);
            if (!value.containsKey(key) || value.get(key) == null) {
                throw new IllegalArgumentException(label + " missing required field: " + key);
            }
        }
    }

    public record ToolExecutionResult(
            String toolName,
            Map<String, Object> input,
            Map<String, Object> output,
            ToolCallView toolCall
    ) {
    }
}
