package com.involutionhell.backend.rag.retrieval.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface RagAskRunRepository {

    void createRunning(
            String runId,
            String correlationId,
            String userId,
            Long conversationId,
            Long userMessageId,
            Long assistantMessageId,
            String requestId,
            String question,
            Integer topK,
            Map<String, Object> filters
    );

    Optional<RagAskRunRecord> findByRequestId(String userId, Long conversationId, String requestId);

    List<RagStaleAskRunRecord> findStaleRunning(OffsetDateTime startedBefore, int limit);

    void markSucceeded(
            String runId,
            Long assistantMessageId,
            String retrievalQuestion,
            List<String> retrievalQueries,
            Object retrievedContexts,
            Object notices,
            boolean generatedByModel,
            boolean degraded
    );

    void markFailed(String runId, String errorCode, String errorMessage, Object notices);
}
