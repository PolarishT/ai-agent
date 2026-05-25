package com.bytedance.ai.agent.workflow;

import java.util.List;
import java.util.Map;

public record WorkflowDefinition(
        String id,
        String startNode,
        List<WorkflowNodeDefinition> nodes,
        List<WorkflowEdgeDefinition> edges
) {
    public WorkflowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public WorkflowNodeDefinition node(String id) {
        return nodes.stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow node: " + id));
    }

    public record WorkflowNodeDefinition(
            String id,
            String type,
            String promptKey,
            ToolBinding tool,
            Map<String, Object> filterDsl,
            Map<String, Object> rankDsl,
            String interruptPolicy,
            String resumeNode
    ) {
        public WorkflowNodeDefinition {
            filterDsl = filterDsl == null ? Map.of() : Map.copyOf(filterDsl);
            rankDsl = rankDsl == null ? Map.of() : Map.copyOf(rankDsl);
        }

        public NodeType nodeType() {
            return NodeType.fromWireName(type);
        }
    }

    public record WorkflowEdgeDefinition(
            String from,
            String to,
            String whenStatus,
            String whenTargetNode,
            boolean defaultEdge
    ) {
    }

    public record ToolBinding(
            String toolName,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            Map<String, Object> inputMapping,
            Map<String, Object> outputMapping
    ) {
        public ToolBinding {
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
            outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
            inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
            outputMapping = outputMapping == null ? Map.of() : Map.copyOf(outputMapping);
        }
    }
}
