package com.bytedance.ai.agent.workflow;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NodeExecutorRegistry {

    private final List<WorkflowNodeExecutor> executors;

    public NodeExecutorRegistry(List<WorkflowNodeExecutor> executors) {
        this.executors = executors == null ? List.of() : List.copyOf(executors);
    }

    public WorkflowNodeExecutor get(NodeType type) {
        return executors.stream()
                .filter(executor -> executor.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No workflow node executor registered for type: " + type));
    }

    public boolean contains(NodeType type) {
        return executors.stream().anyMatch(executor -> executor.supports(type));
    }
}
