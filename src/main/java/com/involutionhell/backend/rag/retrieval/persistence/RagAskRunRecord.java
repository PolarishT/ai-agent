package com.involutionhell.backend.rag.retrieval.persistence;

public record RagAskRunRecord(
        Long id,
        String runId,
        String correlationId,
        String userId,
        Long conversationId,
        Long userMessageId,
        Long assistantMessageId,
        String requestId,
        String question,
        String status,
        String errorCode,
        String errorMessage
) {
}
