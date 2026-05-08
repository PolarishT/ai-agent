package com.involutionhell.backend.rag.retrieval.api;

import java.time.OffsetDateTime;

public record RagConversationSummaryView(
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
