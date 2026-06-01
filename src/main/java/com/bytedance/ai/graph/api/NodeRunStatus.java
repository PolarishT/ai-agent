package com.bytedance.ai.graph.api;

/**
 * Graph 节点运行状态枚举。
 */
public enum NodeRunStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
    WAITING_CLARIFICATION,
    WAITING_CONFIRMATION
}
