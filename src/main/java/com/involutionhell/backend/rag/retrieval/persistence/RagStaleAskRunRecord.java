package com.involutionhell.backend.rag.retrieval.persistence;

import java.time.OffsetDateTime;

public record RagStaleAskRunRecord(
        String runId,
        Long conversationId,
        Long assistantMessageId,
        OffsetDateTime startedAt
) {
}
