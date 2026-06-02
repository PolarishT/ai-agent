package com.bytedance.ai.graph.conversation.context;

/**
 * Generic lifecycle for rows in agent_context_items.
 */
public enum ConversationContextItemStatus {
    ACTIVE,
    CONSUMED,
    SUPERSEDED,
    CANCELLED,
    COMPLETED,
    FAILED,
    EXPIRED
}
