package com.bytedance.ai.agent.workflow;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EdgeResolver {

    public static final String TERMINAL = "__terminal__";

    public String resolve(WorkflowDefinition definition, WorkflowDefinition.WorkflowNodeDefinition node, WorkflowRuntimeState state) {
        if (state.status() != WorkflowStatus.RUNNING) {
            return TERMINAL;
        }
        if (node.nodeType() == NodeType.INTENT_ROUTER && StringUtils.hasText(state.targetNode())) {
            return state.targetNode();
        }
        for (WorkflowDefinition.WorkflowEdgeDefinition edge : definition.edges()) {
            if (!node.id().equals(edge.from())) {
                continue;
            }
            if (StringUtils.hasText(edge.whenStatus()) && !edge.whenStatus().equals(state.status().name())) {
                continue;
            }
            if (StringUtils.hasText(edge.whenTargetNode()) && !edge.whenTargetNode().equals(state.targetNode())) {
                continue;
            }
            if (!edge.isDefaultEdge() || !StringUtils.hasText(state.targetNode())) {
                return edge.to();
            }
        }
        return TERMINAL;
    }
}
