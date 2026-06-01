package com.bytedance.ai.shared.support;

/**
 * Structured logging field names and common values used by RAG logs.
 */
public final class RagLogFields {

    public static final String EVENT_NAME = "event.name";
    public static final String EVENT_OUTCOME = "event.outcome";
    public static final String EVENT_REASON = "event.reason";

    public static final String RAG_CORRELATION_ID = "rag.correlation_id";
    public static final String RAG_DOCUMENT_ID = "rag.document_id";
    public static final String RAG_CONTENT_SHA = "rag.content_sha";
    public static final String RAG_MESSAGE_ID = "rag.message_id";
    public static final String RAG_DELIVERY_ATTEMPT = "rag.delivery_attempt";
    public static final String RAG_MAX_ATTEMPTS = "rag.max_attempts";
    public static final String RAG_TRIGGER_TYPE = "rag.trigger_type";
    public static final String RAG_TRIGGERED_BY = "rag.triggered_by";
    public static final String RAG_INDEX_GENERATION = "rag.index_generation";
    public static final String RAG_INDEX_STAGE = "rag.index_stage";
    public static final String RAG_RETRYABLE = "rag.retryable";
    public static final String RAG_ELAPSED_MS = "rag.elapsed_ms";
    public static final String RAG_CHUNK_COUNT = "rag.chunk_count";
    public static final String RAG_ERROR_SUMMARY = "rag.error_summary";

    public static final String OUTCOME_STARTED = "started";
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";
    public static final String OUTCOME_SKIPPED = "skipped";
    public static final String OUTCOME_RETRY = "retry";

    private RagLogFields() {
    }

    public static String documentCorrelationId(Long documentId, String contentSha256) {
        return "document:" + documentId + ":" + RagLogHelper.shortSha(contentSha256);
    }

    public static String messageCorrelationId(String messageId) {
        return "message:" + (messageId == null || messageId.isBlank() ? "unknown" : messageId);
    }
}
