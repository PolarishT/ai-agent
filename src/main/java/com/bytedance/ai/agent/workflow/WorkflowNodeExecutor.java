package com.bytedance.ai.agent.workflow;

import java.util.Map;

public interface WorkflowNodeExecutor {

    boolean supports(NodeType type);

    Map<String, Object> execute(WorkflowExecution execution, WorkflowDefinition.WorkflowNodeDefinition node) throws Exception;
}
