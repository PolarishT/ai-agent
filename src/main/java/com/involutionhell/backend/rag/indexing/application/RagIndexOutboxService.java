package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.indexing.messaging.RagIndexEventPublisher;
import com.involutionhell.backend.rag.indexing.model.RagIndexOutboxEventType;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowCommand;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowTriggerType;
import com.involutionhell.backend.rag.indexing.service.RagIndexingMetrics;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRecord;
import com.involutionhell.backend.rag.shared.support.RagLogFields;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 负责管理索引消息 Outbox 的入队与分发。
 */
@Service
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "rag.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagIndexOutboxService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexOutboxService.class);

    private final RagProperties ragProperties;
    private final RagIndexOutboxRepository outboxRepository;
    private final RagIndexEventPublisher indexEventPublisher;
    private final IndexWorkflowService workflowService;
    private final RagIndexingMetrics indexingMetrics;

    public RagIndexOutboxService(
            RagProperties ragProperties,
            RagIndexOutboxRepository outboxRepository,
            RagIndexEventPublisher indexEventPublisher,
            IndexWorkflowService workflowService,
            RagIndexingMetrics indexingMetrics
    ) {
        this.ragProperties = ragProperties;
        this.outboxRepository = outboxRepository;
        this.indexEventPublisher = indexEventPublisher;
        this.workflowService = workflowService;
        this.indexingMetrics = indexingMetrics;
    }

    public void enqueue(Long documentId, String contentSha256) {
        outboxRepository.enqueue(documentId, contentSha256, RagIndexOutboxEventType.INDEX_DOCUMENT);
        log.debug(
                "RAG outbox event enqueued: documentId={}, contentSha={}, eventType={}",
                documentId,
                RagLogHelper.shortSha(contentSha256),
                RagIndexOutboxEventType.INDEX_DOCUMENT
        );
    }

    public Optional<RagIndexOutboxRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256) {
        return outboxRepository.findByDocumentIdAndContentSha256(documentId, contentSha256);
    }

    public void deleteByDocumentId(Long documentId) {
        outboxRepository.deleteByDocumentId(documentId);
    }

    public int dispatchPendingBatch() {
        List<RagIndexOutboxRecord> events = outboxRepository.findDispatchable(
                OffsetDateTime.now(),
                ragProperties.outbox().batchSize()
        );
        int dispatched = 0;
        for (RagIndexOutboxRecord event : events) {
            if (!outboxRepository.markSending(event.id())) {
                continue;
            }
            String publishedMessageId;
            try {
                publishedMessageId = indexEventPublisher.publish(event.documentId(), event.contentSha256());
            } catch (Exception exception) {
                handleDispatchException(event, exception);
                continue;
            }

            try {
                outboxRepository.markSent(event.id(), publishedMessageId);
                indexingMetrics.recordOutboxDispatchSuccess();
                log.atInfo()
                        .addKeyValue(RagLogFields.EVENT_NAME, "rag.outbox.event.published")
                        .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                        .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(event.documentId(), event.contentSha256()))
                        .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, event.documentId())
                        .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(event.contentSha256()))
                        .addKeyValue(RagLogFields.RAG_MESSAGE_ID, publishedMessageId)
                        .addKeyValue("rag.outbox_id", event.id())
                        .addKeyValue("rag.outbox_event_type", event.eventType())
                        .addKeyValue("rag.outbox_attempt_count", event.attemptCount())
                        .log("RAG outbox event published");
                dispatched++;
            } catch (Exception exception) {
                indexingMetrics.recordOutboxDispatchFailure("sent_confirmation");
                log.error(
                        "RAG outbox was published but local sent confirmation failed; event remains SENDING for consumer confirmation: eventId={}, documentId={}, contentSha={}, producerMessageId={}, error={}",
                        event.id(),
                        event.documentId(),
                        RagLogHelper.shortSha(event.contentSha256()),
                        publishedMessageId,
                        RagLogHelper.errorSummary(exception),
                        exception
                );
            }
        }
        return dispatched;
    }

    private void handleDispatchException(RagIndexOutboxRecord event, Exception exception) {
        String errorMessage = abbreviate(exception.getMessage());
        OffsetDateTime nextAttemptAt = OffsetDateTime.now()
                .plusNanos(ragProperties.outbox().retryBackoffMillis() * 1_000_000L);
        outboxRepository.markFailed(event.id(), errorMessage, nextAttemptAt);
        indexingMetrics.recordOutboxDispatchFailure("publish");
        recordDispatchRetry(event, errorMessage, nextAttemptAt);
        log.warn(
                "RAG outbox dispatch failed and will retry later: eventId={}, documentId={}, contentSha={}, nextAttemptAt={}, error={}",
                event.id(),
                event.documentId(),
                RagLogHelper.shortSha(event.contentSha256()),
                nextAttemptAt,
                errorMessage
        );
    }

    private void recordDispatchRetry(
            RagIndexOutboxRecord event,
            String errorMessage,
            OffsetDateTime nextAttemptAt
    ) {
        try {
            workflowService.retry(IndexWorkflowCommand.of(
                            event.documentId(),
                            event.contentSha256(),
                            IndexWorkflowTriggerType.SYSTEM,
                            "rag-index-outbox-dispatcher"
                    )
                    .withFailure("dispatch", "Outbox dispatch failed: " + errorMessage)
                    .withNote("Outbox dispatch failed, waiting retry at " + nextAttemptAt)
                    .withMetadata("outboxEventId", event.id())
                    .withMetadata("outboxStatus", event.status())
                    .withMetadata("outboxAttemptCount", event.attemptCount()));
        } catch (Exception exception) {
            log.warn(
                    "RAG outbox dispatch retry transition failed: eventId={}, documentId={}, contentSha={}, error={}",
                    event.id(),
                    event.documentId(),
                    RagLogHelper.shortSha(event.contentSha256()),
                    RagLogHelper.errorSummary(exception)
            );
        }
    }

    public int resetStuckSendingEvents() {
        OffsetDateTime cutoff = OffsetDateTime.now()
                .minusNanos(ragProperties.outbox().sendingStaleMillis() * 1_000_000L);
        List<RagIndexOutboxRecord> stuckEvents = outboxRepository.findStuckSendingBefore(
                cutoff,
                ragProperties.recovery().batchSize()
        );
        for (RagIndexOutboxRecord stuckEvent : stuckEvents) {
            if (deliveryAlreadyConfirmed(stuckEvent)) {
                continue;
            }
            outboxRepository.resetForRetry(
                    stuckEvent.id(),
                    "Outbox SENDING 状态超时，已重置为 FAILED 等待补偿",
                    OffsetDateTime.now()
            );
        }
        return stuckEvents.size();
    }

    private boolean deliveryAlreadyConfirmed(RagIndexOutboxRecord event) {
        return outboxRepository.findByDocumentIdAndContentSha256(event.documentId(), event.contentSha256())
                .filter(current -> current.id().equals(event.id()))
                .filter(current -> current.consumedAt() != null || "SENT".equals(current.status()))
                .isPresent();
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
