package com.involutionhell.backend.rag.retrieval.persistence;

import java.time.OffsetDateTime;

public record RagConversationCursor(
        OffsetDateTime lastMessageAt,
        Long id
) {
}
