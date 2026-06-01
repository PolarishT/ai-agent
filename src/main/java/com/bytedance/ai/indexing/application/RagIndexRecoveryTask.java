package com.bytedance.ai.indexing.application;

import com.bytedance.ai.document.spi.DocumentIndexingSpi;
import com.bytedance.ai.document.spi.DocumentIndexingView;
import com.bytedance.ai.indexing.model.RagIndexJobStatus;
import com.bytedance.ai.indexing.model.RagIndexOutboxStatus;
import com.bytedance.ai.indexing.model.RagIndexStage;
import com.bytedance.ai.indexing.persistence.RagIndexJobRecord;
import com.bytedance.ai.indexing.persistence.RagIndexJobRepository;
import com.bytedance.ai.indexing.persistence.RagIndexOutboxRecord;
import com.bytedance.ai.indexing.service.RagIndexingMetrics;
import com.bytedance.ai.indexing.workflow.IndexWorkflowCommand;
import com.bytedance.ai.indexing.workflow.IndexWorkflowEvent;
import com.bytedance.ai.indexing.workflow.IndexWorkflowService;
import com.bytedance.ai.indexing.workflow.IndexWorkflowTriggerType;
import com.bytedance.ai.shared.model.RagDocumentStatus;
import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 索引补偿定时任务。
 *
 * <p>周期性扫描长期停留在 PENDING / PROCESSING / FAILED / DELETING 的文档，
 * 修复卡住的 outbox、孤儿 job 和删除清理残留，尽量把离线索引链路恢复到可继续推进的状态。
 */
@Component
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "rag.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "rag.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagIndexRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(RagIndexRecoveryTask.class);

    private final RagProperties ragProperties;
    private final DocumentIndexingSpi documentIndexingSpi;
    private final RagIndexJobRepository jobRepository;
    private final IndexWorkflowService workflowService;
    private final RagIndexOutboxService outboxService;
    private final RagIndexingService indexingService;
    private final RagIndexingMetrics indexingMetrics;
    private final TransactionTemplate transactionTemplate;
    private final RagIndexMaintenanceService ragIndexMaintenanceService;

    public RagIndexRecoveryTask(
            RagProperties ragProperties,
            DocumentIndexingSpi documentIndexingSpi,
            RagIndexJobRepository jobRepository,
            IndexWorkflowService workflowService,
            RagIndexOutboxService outboxService,
            RagIndexingService indexingService,
            RagIndexingMetrics indexingMetrics,
            TransactionTemplate transactionTemplate, RagIndexMaintenanceService ragIndexMaintenanceService
    ) {
        this.ragProperties = ragProperties;
        this.documentIndexingSpi = documentIndexingSpi;
        this.jobRepository = jobRepository;
        this.workflowService = workflowService;
        this.outboxService = outboxService;
        this.indexingService = indexingService;
        this.indexingMetrics = indexingMetrics;
        this.transactionTemplate = transactionTemplate;
        this.ragIndexMaintenanceService = ragIndexMaintenanceService;
    }

    /**
     * 执行一次补偿扫描。
     *
     * <p>该方法由 Spring 调度器周期性触发，会先修复卡住的 outbox 与孤儿 job，
     * 再按文档状态分类重新投递或完成删除清理。
     */
    @Scheduled(fixedDelayString = "${rag.recovery.fixed-delay-millis:6000000}")
    public void recoverStaleDocuments() {
        int resetOutboxCount = outboxService.resetStuckSendingEvents();
        if (resetOutboxCount > 0) {
            log.warn("Detected stuck RAG outbox events and reset them for retry: count={}", resetOutboxCount);
        }

        int repairedOrphans = ragIndexMaintenanceService.executeOrphanCleanup();
        if (repairedOrphans > 0) {
            log.warn("Detected stale orphaned RAG indexing jobs and repaired them: count={}", repairedOrphans);
        }

        int recovered = 0;
        List<DocumentIndexingView> pendingDocuments = documentIndexingSpi.findPendingBefore(
                OffsetDateTime.now().minusNanos(ragProperties.recovery().pendingStaleMillis() * 1_000_000L),
                ragProperties.recovery().batchSize()
        );
        List<DocumentIndexingView> processingDocuments = documentIndexingSpi.findProcessingBefore(
                OffsetDateTime.now().minusNanos(ragProperties.recovery().processingStaleMillis() * 1_000_000L),
                ragProperties.recovery().batchSize()
        );
        List<DocumentIndexingView> failedDocuments = documentIndexingSpi.findFailedBefore(
                OffsetDateTime.now().minusNanos(ragProperties.recovery().failedRetryMillis() * 1_000_000L),
                ragProperties.recovery().batchSize()
        );
        List<DocumentIndexingView> deletingDocuments = documentIndexingSpi.findDeletingBefore(
                OffsetDateTime.now().minusNanos(ragProperties.recovery().deletingStaleMillis() * 1_000_000L),
                ragProperties.recovery().batchSize()
        );

        indexingMetrics.recordRecoveryScan("pending", pendingDocuments.size());
        indexingMetrics.recordRecoveryScan("processing", processingDocuments.size());
        indexingMetrics.recordRecoveryScan("failed", failedDocuments.size());
        indexingMetrics.recordRecoveryScan("deleting", deletingDocuments.size());

        recovered += requeueDocuments(
                pendingDocuments,
                "文档长时间停留在 PENDING，已由补偿任务重新投递",
                "pending"
        );
        recovered += requeueDocuments(
                processingDocuments,
                "文档长时间停留在 PROCESSING，已由补偿任务重新投递",
                "processing"
        );
        recovered += requeueDocuments(
                failedDocuments,
                "文档长时间停留在 FAILED，已由补偿任务重新投递",
                "failed"
        );
        recovered += finalizeDeletingDocuments(deletingDocuments);

        if (recovered > 0) {
            log.info("RAG recovery task requeued stale indexing documents: count={}", recovered);
        }
    }

    private int requeueDocuments(List<DocumentIndexingView> documents, String reason, String category) {
        int count = 0;
        for (DocumentIndexingView document : documents) {

            if (RagDocumentStatus.DELETING.name().equals(document.status())) {
                indexingMetrics.recordRecoveryOutcome(category, "skip");
                continue;
            }
            RagIndexJobRecord job = currentJob(document);
            if (!shouldRecover(document, job)) {
                indexingMetrics.recordRecoveryOutcome(category, "skip");
                log.debug(
                        "RAG recovery skipped because job state does not require compensation yet: documentId={}, contentSha={}, documentStatus={}, jobStatus={}, jobStage={}, jobLastEvent={}, attemptCount={}",
                        document.id(),
                        RagLogHelper.shortSha(document.contentSha256()),
                        document.status(),
                        job.status(),
                        job.stage(),
                        job.lastEvent(),
                        job.attemptCount()
                );
                continue;
            }
            if (alreadyHasPendingOutbox(document)) {
                indexingMetrics.recordRecoveryOutcome(category, "skip");
                continue;
            }
            IndexWorkflowCommand command = IndexWorkflowCommand.of(
                            document.id(),
                            document.contentSha256(),
                            IndexWorkflowTriggerType.RECOVERY,
                            "rag-index-recovery"
                    )
                    .withNote(reason);
            try {

                transactionTemplate.executeWithoutResult((_) -> {
                    workflowService.queue(command);
                    workflowService.dispatch(command);
                    outboxService.enqueue(document.id(), document.contentSha256());
                });
                indexingMetrics.recordRecoveryOutcome(category, "success");
                count++;
            } catch (Exception exception) {
                indexingMetrics.recordRecoveryOutcome(category, "failure");
                recordRecoveryFailure(
                        command,
                        "恢复任务重新投递失败: " + RagLogHelper.errorSummary(exception)
                );
                log.warn(
                        "RAG recovery requeue failed but will continue with remaining documents: documentId={}, contentSha={}, error={}",
                        document.id(),
                        RagLogHelper.shortSha(document.contentSha256()),
                        RagLogHelper.errorSummary(exception)
                );
            }
        }
        return count;
    }

    private int finalizeDeletingDocuments(List<DocumentIndexingView> documents) {
        int count = 0;
        for (DocumentIndexingView document : documents) {
            try {
                indexingService.deleteDocumentIndex(document.id());
                documentIndexingSpi.deleteById(document.id());
                indexingMetrics.recordDeleteCleanup("success");
                indexingMetrics.recordRecoveryOutcome("deleting", "success");
                count++;
            } catch (Exception exception) {
                indexingMetrics.recordDeleteCleanup("failure");
                indexingMetrics.recordRecoveryOutcome("deleting", "failure");
                recordRecoveryFailure(
                        IndexWorkflowCommand.of(
                                        document.id(),
                                        document.contentSha256(),
                                        IndexWorkflowTriggerType.RECOVERY,
                                        "rag-index-recovery"
                                )
                                .withNote("删除补偿执行失败"),
                        "删除补偿失败: " + RagLogHelper.errorSummary(exception)
                );
                log.warn(
                        "RAG delete recovery failed and will retry later: documentId={}, contentSha={}, error={}",
                        document.id(),
                        RagLogHelper.shortSha(document.contentSha256()),
                        RagLogHelper.errorSummary(exception)
                );
            }
        }
        return count;
    }

    private boolean alreadyHasPendingOutbox(DocumentIndexingView document) {
        return outboxService.findByDocumentIdAndContentSha256(document.id(), document.contentSha256())
                .map(RagIndexOutboxRecord::status)
                .filter(status -> !RagIndexOutboxStatus.SENT.name().equals(status))
                .isPresent();
    }

    private RagIndexJobRecord currentJob(DocumentIndexingView document) {
        return jobRepository.findByDocumentIdAndContentSha256(document.id(), document.contentSha256())
                .orElse(null);
    }

    private boolean shouldRecover(DocumentIndexingView document, RagIndexJobRecord job) {
        if (job == null) {
            return true;
        }
        if (RagIndexJobStatus.SUCCEEDED.name().equals(job.status())
                || RagIndexJobStatus.SKIPPED.name().equals(job.status())) {
            return false;
        }

        String documentStatus = document.status();
        if (RagDocumentStatus.PENDING.name().equals(documentStatus)) {
            return shouldRecoverPending(job);
        }
        if (RagDocumentStatus.PROCESSING.name().equals(documentStatus)) {
            return shouldRecoverProcessing(job);
        }
        if (RagDocumentStatus.FAILED.name().equals(documentStatus)) {
            return shouldRecoverFailed(job);
        }
        return false;
    }

    private boolean shouldRecoverPending(RagIndexJobRecord job) {
        if (RagIndexJobStatus.RUNNING.name().equals(job.status())) {
            return isJobStale(job, ragProperties.recovery().processingStaleMillis());
        }
        if (RagIndexJobStatus.QUEUED.name().equals(job.status())) {
            return isJobStale(job, ragProperties.recovery().pendingStaleMillis())
                    && isQueueLikeStage(job.stage(), job.lastEvent(), job.attemptCount());
        }
        if (RagIndexJobStatus.FAILED.name().equals(job.status())) {
            return isJobStale(job, ragProperties.recovery().failedRetryMillis());
        }
        return true;
    }

    private boolean shouldRecoverProcessing(RagIndexJobRecord job) {
        if (RagIndexJobStatus.RUNNING.name().equals(job.status())) {
            return isJobStale(job, ragProperties.recovery().processingStaleMillis())
                    && isProcessingStage(job.stage());
        }
        if (RagIndexJobStatus.QUEUED.name().equals(job.status())) {
            return isJobStale(job, ragProperties.recovery().processingStaleMillis());
        }
        if (RagIndexJobStatus.FAILED.name().equals(job.status())) {
            return isJobStale(job, ragProperties.recovery().failedRetryMillis());
        }
        return false;
    }

    private boolean shouldRecoverFailed(RagIndexJobRecord job) {
        if (RagIndexJobStatus.FAILED.name().equals(job.status())) {
            return isJobStale(job, ragProperties.recovery().failedRetryMillis());
        }
        if (RagIndexJobStatus.QUEUED.name().equals(job.status())
                || RagIndexJobStatus.RUNNING.name().equals(job.status())) {
            return isJobStale(job, ragProperties.recovery().processingStaleMillis());
        }
        return true;
    }

    private boolean isQueueLikeStage(String stage, String lastEvent, Integer attemptCount) {
        boolean queueLikeStage = stage == null
                || RagIndexStage.QUEUED.name().equals(stage)
                || RagIndexStage.DISPATCHING.name().equals(stage);
        boolean queueLikeEvent = lastEvent == null
                || IndexWorkflowEvent.QUEUE.name().equals(lastEvent)
                || IndexWorkflowEvent.DISPATCH.name().equals(lastEvent)
                || IndexWorkflowEvent.RETRY.name().equals(lastEvent);
        return queueLikeStage && queueLikeEvent && (attemptCount == null || attemptCount >= 0);
    }

    private boolean isProcessingStage(String stage) {
        return RagIndexStage.PREPARING.name().equals(stage)
                || RagIndexStage.CHUNKING.name().equals(stage)
                || RagIndexStage.SAVE_CHUNKS.name().equals(stage)
                || RagIndexStage.VECTOR_INDEXING.name().equals(stage)
                || RagIndexStage.COMMIT_INDEX.name().equals(stage)
                || RagIndexStage.DISPATCHING.name().equals(stage);
    }

    private boolean isJobStale(RagIndexJobRecord job, long staleMillis) {
        if (job == null) {
            return true;
        }
        OffsetDateTime reference = firstNonNull(job.updatedAt(), job.startedAt(), job.createdAt());
        if (reference == null) {
            return true;
        }
        return reference.isBefore(OffsetDateTime.now().minusNanos(staleMillis * 1_000_000L));
    }

    private OffsetDateTime firstNonNull(OffsetDateTime... candidates) {
        for (OffsetDateTime candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private void recordRecoveryFailure(IndexWorkflowCommand command, String note) {
        if (currentJobIsTerminal(command)) {
            log.debug(
                    "RAG recovery failure note skipped because job is already terminal: documentId={}, contentSha={}",
                    command.documentId(),
                    RagLogHelper.shortSha(command.contentSha256())
            );
            return;
        }
        try {
            workflowService.skip(
                    command
                            .withNote(note)
                            .withFailure("recovery", note)
                            .withMetadata("recoveryFailure", true)
            );
        } catch (Exception exception) {
            log.warn(
                    "RAG recovery failure note could not be recorded: documentId={}, contentSha={}, error={}",
                    command.documentId(),
                    RagLogHelper.shortSha(command.contentSha256()),
                    RagLogHelper.errorSummary(exception)
            );
        }
    }

    private boolean currentJobIsTerminal(IndexWorkflowCommand command) {
        return jobRepository.findByDocumentIdAndContentSha256(command.documentId(), command.contentSha256())
                .map(job -> RagIndexJobStatus.SUCCEEDED.name().equals(job.status())
                        || RagIndexJobStatus.FAILED.name().equals(job.status())
                        || RagIndexJobStatus.SKIPPED.name().equals(job.status())
                        || "FAILED".equals(job.stage()))
                .orElse(false);
    }
}
