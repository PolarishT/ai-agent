package com.bytedance.ai.agent.workflow;

/**
 * 工作流运行时状态的持久化抽象。
 *
 * <ul>
 *     <li>{@link InMemoryWorkflowStateStore} 用于单测与单实例本地启动。</li>
 *     <li>{@link JdbcWorkflowStateStore} 用于真实部署，跨进程恢复 WAITING_SELECTION / WAITING_CONFIRMATION。</li>
 * </ul>
 */
public interface WorkflowStateStore {

    WorkflowRuntimeState restore(String conversationId);

    void save(String conversationId, WorkflowRuntimeState state);

    void clear(String conversationId);
}
