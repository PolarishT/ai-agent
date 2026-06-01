package com.bytedance.ai.graph.api;

import java.util.Locale;

/**
 * 智能导购流式事件类型枚举。
 */
public enum AgentStreamEventType {
    TURN_STARTED,
    WORKFLOW_STARTED,
    NODE_STARTED,
    NODE_COMPLETED,
    NODE_FAILED,
    ANSWER_DELTA,
    ANSWER_COMPLETED,
    TURN_COMPLETED,
    TURN_ERROR;

    public String eventName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '.');
    }
}
