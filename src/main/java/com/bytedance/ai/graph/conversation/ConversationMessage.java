package com.bytedance.ai.graph.conversation;

import java.time.OffsetDateTime;

/**
 * 会话消息记录，保存一次用户输入、助手回复以及对应的工作流状态。
 */
public record ConversationMessage(
        Long id,
        String messageId,
        Long conversationId,
        String role,
        String content,
        String status,
        String correlationId,
        Integer sequenceNo,
        OffsetDateTime createdAt
) {
}
