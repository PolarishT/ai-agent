package com.bytedance.ai.agent.workflow;

public enum WorkflowStatus {
    RUNNING,
    END,
    WAITING_CONFIRMATION,
    WAITING_SLOT,
    WAITING_SELECTION,
    FAILED
}
