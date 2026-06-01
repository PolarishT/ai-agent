package com.bytedance.ai.indexing.messaging;

import com.bytedance.ai.indexing.application.RagIndexingService;
import com.bytedance.ai.indexing.model.RagIndexAttemptException;
import com.bytedance.ai.indexing.model.RagIndexFailure;
import com.bytedance.ai.indexing.service.RagIndexingFailureClassifier;
import com.bytedance.ai.indexing.workflow.IndexWorkflowCommand;
import com.bytedance.ai.indexing.workflow.IndexWorkflowService;
import com.bytedance.ai.indexing.workflow.IndexWorkflowTriggerType;
import com.bytedance.ai.shared.support.RagLogFields;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 未启用 RocketMQ 时，直接在当前进程执行索引。
 */
@Service
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class RagDirectIndexEventPublisher implements RagIndexEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RagDirectIndexEventPublisher.class);

    private final RagIndexingService ragIndexingService;
    private final IndexWorkflowService workflowService;
    private final RagIndexingFailureClassifier failureClassifier;
    private final Executor ragVirtualThreadExecutor;

    public RagDirectIndexEventPublisher(
            RagIndexingService ragIndexingService,
            IndexWorkflowService workflowService,
            RagIndexingFailureClassifier failureClassifier,
            @Qualifier("ragVirtualThreadExecutor") Executor ragVirtualThreadExecutor
    ) {
        this.ragIndexingService = ragIndexingService;
        this.workflowService = workflowService;
        this.failureClassifier = failureClassifier;
        this.ragVirtualThreadExecutor = ragVirtualThreadExecutor;
    }

    @Override
    public String publish(Long documentId, String contentSha256) {
        IndexWorkflowCommand command = IndexWorkflowCommand.of(
                documentId,
                contentSha256,
                IndexWorkflowTriggerType.API,
                "direct-publisher"
        );

        String publishId = "direct-" + documentId + "-" + RagLogHelper.shortSha(contentSha256);
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.direct_publish.started")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_STARTED)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                .addKeyValue(RagLogFields.RAG_TRIGGER_TYPE, command.triggerType())
                .addKeyValue(RagLogFields.RAG_TRIGGERED_BY, command.triggeredBy())
                .addKeyValue("rag.publish_id", publishId)
                .log("RocketMQ is disabled, scheduling in-process RAG indexing");

        try {
            ragVirtualThreadExecutor.execute(() -> executeIndexing(documentId, contentSha256, command));
            return publishId;
        } catch (RejectedExecutionException exception) {
            String reason = "executor_rejected";
            String errorMessage = "Direct RAG indexing task was rejected by executor: "
                    + abbreviate(exception.getMessage());

            failDirectIndexing(command, reason, errorMessage);

            log.atError()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.direct_publish.failed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                    .addKeyValue(RagLogFields.EVENT_REASON, reason)
                    .addKeyValue("rag.publish_id", publishId)
                    .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                    .setCause(exception)
                    .log("Direct RAG indexing submission was rejected");

            throw new IllegalStateException(
                    "Failed to schedule direct RAG indexing task: executor rejected submission",
                    exception
            );
        }
    }

    private void executeIndexing(Long documentId, String contentSha256, IndexWorkflowCommand command) {
        try {
            ragIndexingService.indexDocument(documentId, contentSha256, command);
        } catch (IllegalArgumentException exception) {
            ragIndexingService.deleteOrphanedIndexingState(documentId);
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.direct_publish.skipped")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SKIPPED)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                    .addKeyValue(RagLogFields.EVENT_REASON, "document_unavailable")
                    .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                    .log("Direct RAG indexing skipped because document is unavailable");
        } catch (RagIndexAttemptException exception) {
            if (isMissingDocument(exception)) {
                ragIndexingService.deleteOrphanedIndexingState(documentId);
                log.atInfo()
                        .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.direct_publish.skipped")
                        .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SKIPPED)
                        .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                        .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                        .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                        .addKeyValue(RagLogFields.RAG_INDEX_STAGE, exception.getStage())
                        .addKeyValue(RagLogFields.EVENT_REASON, "document_disappeared")
                        .log("Direct RAG indexing cancelled because document disappeared during execution");
            } else {
                failDirectIndexing(command, exception.getReason(), exception.getErrorMessage());
                log.atError()
                        .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.direct_publish.failed")
                        .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                        .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                        .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                        .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                        .addKeyValue(RagLogFields.RAG_INDEX_STAGE, exception.getStage())
                        .addKeyValue(RagLogFields.EVENT_REASON, exception.getReason())
                        .addKeyValue(RagLogFields.RAG_RETRYABLE, exception.isRetryable())
                        .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                        .setCause(exception)
                        .log("Direct RAG indexing failed");
            }
        } catch (Exception exception) {
            RagIndexFailure failure = failureClassifier.classify(exception);
            String errorMessage = "索引失败 [" + failure.reason() + "]: " + abbreviate(exception.getMessage());
            failDirectIndexing(command, failure.reason(), errorMessage);
            log.atError()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.direct_publish.failed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                    .addKeyValue(RagLogFields.EVENT_REASON, failure.reason())
                    .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                    .setCause(exception)
                    .log("Direct RAG indexing failed unexpectedly");
        }
    }

    private void failDirectIndexing(IndexWorkflowCommand command, String reason, String errorMessage) {
        try {
            workflowService.fail(command.withFailure(reason, errorMessage));
        } catch (Exception exception) {
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.direct_publish.transition_failed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(command.documentId(), command.contentSha256()))
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, command.documentId())
                    .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(command.contentSha256()))
                    .addKeyValue(RagLogFields.EVENT_REASON, reason)
                    .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                    .log("Direct RAG indexing could not transition to FAILED; leaving terminal handling to logs");
        }
    }

    private boolean isMissingDocument(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IllegalArgumentException illegalArgumentException
                    && illegalArgumentException.getMessage() != null
                    && illegalArgumentException.getMessage().contains("RAG 文档不存在")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String abbreviate(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        if (message.length() <= 240) {
            return message;
        }
        return message.substring(0, 237) + "...";
    }
}
