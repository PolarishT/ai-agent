package com.involutionhell.backend.rag.retrieval.persistence;

import java.time.OffsetDateTime;

public record RagConversationRecord(
        Long id,
        String conversationId,
        String userId,
        String title,
        String status,
        int messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime lastMessageAt
) {
}
