package com.bytedance.ai.agent.workflow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的简单实现，仅在没有 {@link JdbcWorkflowStateStore} 时作为兜底（如本地单测）。
 */
public class InMemoryWorkflowStateStore implements WorkflowStateStore {

    private final Map<String, WorkflowRuntimeState> states = new ConcurrentHashMap<>();

    @Override
    public WorkflowRuntimeState restore(String conversationId) {
        return states.getOrDefault(conversationId, new WorkflowRuntimeState());
    }

    @Override
    public void save(String conversationId, WorkflowRuntimeState state) {
        if (conversationId == null || state == null) {
            return;
        }
        states.put(conversationId, state);
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId != null) {
            states.remove(conversationId);
        }
    }
}
