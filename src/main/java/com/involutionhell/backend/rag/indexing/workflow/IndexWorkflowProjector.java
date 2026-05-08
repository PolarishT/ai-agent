package com.involutionhell.backend.rag.indexing.workflow;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingSpi;
import com.involutionhell.backend.rag.indexing.model.RagIndexStage;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 把工作流状态投影到 rag_documents 与 rag_index_jobs。
 */
@Component
public class IndexWorkflowProjector {

    private final DocumentIndexingSpi documentIndexingSpi;
    private final RagIndexJobRepository jobRepository;

    public IndexWorkflowProjector(
            DocumentIndexingSpi documentIndexingSpi,
            RagIndexJobRepository jobRepository
    ) {
        this.documentIndexingSpi = documentIndexingSpi;
        this.jobRepository = jobRepository;
    }

    /**
     * 将状态机跃迁结果写回现有业务表。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void project(
            IndexWorkflowState fromState,
            IndexWorkflowState toState,
            IndexWorkflowEvent event,
            IndexWorkflowCommand command
    ) {
        Long documentId = command.documentId();
        String contentSha256 = command.contentSha256();

        switch (toState) {
            case QUEUED -> {
                if (fromState == IndexWorkflowState.NEW || fromState == IndexWorkflowState.FAILED) {
                    // 全新创建或失败重排队，内部使用 INSERT ... ON CONFLICT 防御
                    jobRepository.queue(documentId, contentSha256);
                } else {
                    // 重复排队，基于旧状态进行原子重置
                    RagIndexStage expectedFromStage = toRagIndexStage(fromState);
                    int rows = jobRepository.updateStage(documentId, contentSha256, expectedFromStage, RagIndexStage.QUEUED);
                    requireCasSuccess(rows, "RESET_TO_QUEUED");
                }
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
                if (command.triggerType() == IndexWorkflowTriggerType.RECOVERY && command.note() != null) {
                    documentIndexingSpi.requeue(documentId, command.note());
                } else {
                    documentIndexingSpi.markPending(documentId);
                }
            }
            case DISPATCHING -> {
                RagIndexStage expectedFromStage = toRagIndexStage(fromState);
                int rows = jobRepository.updateStage(documentId, contentSha256, expectedFromStage, RagIndexStage.DISPATCHING);
                requireCasSuccess(rows, "DISPATCHING");
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case PREPARING -> {
                // startAttempt 包含尝试次数 +1 的原子操作
                RagIndexStage expectedFromStage = toRagIndexStage(fromState);
                int rows = jobRepository.startAttempt(documentId, contentSha256, expectedFromStage, command.targetGeneration());
                requireCasSuccess(rows, "PREPARING");
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
                documentIndexingSpi.markProcessing(documentId);
            }
            case CHUNKING -> {
                RagIndexStage expectedFromStage = toRagIndexStage(fromState);
                int rows = jobRepository.updateStage(documentId, contentSha256, expectedFromStage, RagIndexStage.CHUNKING);
                requireCasSuccess(rows, "CHUNKING");
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case SAVE_CHUNKS -> {
                RagIndexStage expectedFromStage = toRagIndexStage(fromState);
                int rows = jobRepository.updateStage(documentId, contentSha256, expectedFromStage, RagIndexStage.SAVE_CHUNKS);
                requireCasSuccess(rows, "SAVE_CHUNKS");
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case VECTOR_INDEXING -> {
                RagIndexStage expectedFromStage = toRagIndexStage(fromState);
                int rows = jobRepository.updateStage(documentId, contentSha256, expectedFromStage, RagIndexStage.VECTOR_INDEXING);
                requireCasSuccess(rows, "VECTOR_INDEXING");
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case COMMIT_INDEX -> {
                RagIndexStage expectedFromStage = toRagIndexStage(fromState);
                int rows = jobRepository.updateStage(documentId, contentSha256, expectedFromStage, RagIndexStage.COMMIT_INDEX);
                requireCasSuccess(rows, "COMMIT_INDEX");
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case COMPLETED -> {
                RagIndexStage expectedFromStage = toRagIndexStage(fromState);
                int rows = jobRepository.markSucceeded(documentId, contentSha256, expectedFromStage, command.targetGeneration());
                requireCasSuccess(rows, "COMPLETED");
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
                documentIndexingSpi.markIndexed(
                        documentId,
                        command.targetGeneration(),
                        command.chunkCount() == null ? 0 : command.chunkCount(),
                        OffsetDateTime.now()
                );
            }
            case FAILED -> {
                RagIndexStage expectedFromStage = toRagIndexStage(fromState);
                int rows = jobRepository.markFailed(
                        documentId,
                        contentSha256,
                        expectedFromStage,
                        toFailureStage(fromState),
                        defaultErrorMessage(command)
                );
                requireCasSuccess(rows, "FAILED");
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
                documentIndexingSpi.markFailed(documentId, defaultErrorMessage(command));
            }
            case SKIPPED -> {
                RagIndexStage expectedFromStage = toRagIndexStage(fromState);
                int rows = jobRepository.markSkipped(documentId, contentSha256, expectedFromStage, defaultSkipReason(command));
                requireCasSuccess(rows, "SKIPPED");
                jobRepository.annotateEvent(documentId, contentSha256, event.name());
            }
            case NEW -> {
                // no-op
            }
        }
    }

    private RagIndexStage toFailureStage(IndexWorkflowState state) {
        return switch (state) {
            case DISPATCHING -> RagIndexStage.DISPATCHING;
            case PREPARING -> RagIndexStage.PREPARING;
            case CHUNKING -> RagIndexStage.CHUNKING;
            case SAVE_CHUNKS -> RagIndexStage.SAVE_CHUNKS;
            case VECTOR_INDEXING -> RagIndexStage.VECTOR_INDEXING;
            case COMMIT_INDEX -> RagIndexStage.COMMIT_INDEX;
            case COMPLETED -> RagIndexStage.COMPLETED;
            case SKIPPED -> RagIndexStage.SKIPPED;
            case NEW, QUEUED, FAILED -> RagIndexStage.QUEUED;
        };
    }

    private String defaultErrorMessage(IndexWorkflowCommand command) {
        if (command.errorMessage() != null && !command.errorMessage().isBlank()) {
            return command.errorMessage();
        }
        if (command.failureReason() != null && !command.failureReason().isBlank()) {
            return "索引失败 [" + command.failureReason() + "]";
        }
        return "索引失败";
    }

    private String defaultSkipReason(IndexWorkflowCommand command) {
        if (command.note() != null && !command.note().isBlank()) {
            return command.note();
        }
        if (command.errorMessage() != null && !command.errorMessage().isBlank()) {
            return command.errorMessage();
        }
        return "索引任务被跳过";
    }

    /**
     * 将内部状态机状态转换为持久化层的业务 Stage 枚举。
     */
    private RagIndexStage toRagIndexStage(IndexWorkflowState state) {
        if (state == null || state == IndexWorkflowState.NEW) {
            return null;
        }
        // 基于命名的直接转换，语义清晰
        return RagIndexStage.valueOf(state.name());
    }

    /**
     * 校验 CAS 更新是否成功
     */
    private void requireCasSuccess(int updatedRows, String stageName) {
        if (updatedRows == 0) {
            throw new OptimisticLockingFailureException(
                    "RAG 索引任务状态冲突：尝试跃迁至 [" + stageName + "] 时发现任务已被其他进程更新"
            );
        }
    }
}
