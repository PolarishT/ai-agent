package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.indexing.model.RagIndexJobStatus;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowCommand;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowTriggerType;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class RagIndexMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(RagIndexMaintenanceService.class);

    private final RagProperties ragProperties;
    private final RagIndexJobRepository jobRepository;
    private final RagIndexingService indexingService;
    private final IndexWorkflowService workflowService;
    private final TransactionTemplate transactionTemplate;

    public RagIndexMaintenanceService(
            RagProperties ragProperties,
            RagIndexJobRepository jobRepository,
            RagIndexingService indexingService,
            IndexWorkflowService workflowService,
            TransactionTemplate transactionTemplate
    ) {
        this.ragProperties = ragProperties;
        this.jobRepository = jobRepository;
        this.indexingService = indexingService;
        this.workflowService = workflowService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 扫描并清理长期未完成的孤儿索引任务。
     *
     * @return 成功修复的孤儿任务数量
     */
    public int executeOrphanCleanup() {
        OffsetDateTime timeoutThreshold = OffsetDateTime.now()
                .minusNanos(ragProperties.recovery().processingStaleMillis() * 1_000_000L);
        List<RagIndexJobRecord> staleJobs = jobRepository.findStaleJobs(
                timeoutThreshold,
                ragProperties.recovery().batchSize()
        );
        int repaired = 0;

        for (RagIndexJobRecord job : staleJobs) {
            Boolean success = transactionTemplate.execute(status -> {
                try {
                    return processSingleOrphan(job);
                } catch (Exception e) {
                    status.setRollbackOnly();
                    log.error(
                            "自动清理孤儿任务失败: jobId={}, documentId={}, contentSha={}, error={}",
                            job.id(),
                            job.documentId(),
                            RagLogHelper.shortSha(job.contentSha256()),
                            RagLogHelper.errorSummary(e),
                            e
                    );
                    return Boolean.FALSE;
                }
            });
            if (Boolean.TRUE.equals(success)) {
                repaired++;
            }
        }
        return repaired;
    }

    private boolean processSingleOrphan(RagIndexJobRecord job) {
        if (isTerminal(job)) {
            log.info(
                    "跳过已处于终态的孤儿索引任务: jobId={}, documentId={}, contentSha={}, status={}",
                    job.id(),
                    job.documentId(),
                    RagLogHelper.shortSha(job.contentSha256()),
                    job.status()
            );
            return false;
        }

        log.warn("发现异常超时的索引任务，启动自动修复: documentId={}, generation={}",
                job.documentId(), job.targetGeneration());

        if (job.targetGeneration() == null) {
            workflowService.fail(IndexWorkflowCommand.of(
                    job.documentId(),
                    job.contentSha256(),
                    IndexWorkflowTriggerType.SYSTEM,
                    "orphan-cleaner"
            ).withFailure("timeout", "任务处理超时且缺少 targetGeneration，系统已标记为失败等待人工介入"));
            return true;
        }

        // 1. 回收该次 attempt 遗留的 generation 数据，避免半路崩溃留下脏向量或脏 chunk。
        indexingService.deleteOrphanedIndexingState(job.documentId(), job.targetGeneration());

        // 2. 将 job 推向失败终态，留下审计痕迹，避免无限反复扫描同一条坏任务。
        workflowService.fail(IndexWorkflowCommand.of(
                job.documentId(),
                job.contentSha256(),
                IndexWorkflowTriggerType.SYSTEM,
                "orphan-cleaner"
        ).withFailure("timeout", "任务处理超时且进程已失联，系统自动回收资源"));
        return true;
    }

    private boolean isTerminal(RagIndexJobRecord job) {
        return RagIndexJobStatus.SUCCEEDED.name().equals(job.status())
                || RagIndexJobStatus.FAILED.name().equals(job.status())
                || RagIndexJobStatus.SKIPPED.name().equals(job.status());
    }
}
