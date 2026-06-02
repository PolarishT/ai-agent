package com.bytedance.ai.graph.conversation.context;

/**
 * Central runtime context item categories shared by all guide workflows.
 */
public enum ConversationContextItemType {
    PRODUCT_CANDIDATE,
    FOCUS,
    PENDING_CLARIFICATION,
    CART_SNAPSHOT,
    ORDER_CONTEXT,
    LAST_RESULT,
    MEMORY_SLOT,
    TASK_CHAIN
}
