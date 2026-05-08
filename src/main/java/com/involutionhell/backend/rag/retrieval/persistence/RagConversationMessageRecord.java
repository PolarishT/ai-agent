package com.involutionhell.backend.rag.retrieval.persistence;

import java.time.OffsetDateTime;

public record RagConversationMessageRecord(
        Long id,
        String messageId,
        Long conversationId,
        String role,
        String content,
        String status,
        Integer tokenCount,
        String correlationId,
        int sequenceNo,
        OffsetDateTime createdAt
) {
}
