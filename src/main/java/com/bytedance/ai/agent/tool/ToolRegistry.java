package com.bytedance.ai.agent.tool;

import com.bytedance.ai.agent.api.IntentType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ToolRegistry {

    private final List<AgentToolCallback> callbacks;

    public ToolRegistry(List<AgentToolCallback> callbacks) {
        this.callbacks = callbacks == null ? List.of() : List.copyOf(callbacks);
    }

    public List<AgentToolCallback> plan(IntentType intent) {
        if (intent == null) {
            return List.of();
        }
        return callbacks.stream()
                .filter(callback -> callback.handles().contains(intent))
                .toList();
    }

    public Optional<AgentToolCallback> findByName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return callbacks.stream()
                .filter(callback -> toolName.equals(callback.getToolDefinition().name()))
                .findFirst();
    }

    public List<org.springframework.ai.tool.definition.ToolDefinition> toolDefinitions() {
        return callbacks.stream()
                .map(AgentToolCallback::getToolDefinition)
                .toList();
    }
}
