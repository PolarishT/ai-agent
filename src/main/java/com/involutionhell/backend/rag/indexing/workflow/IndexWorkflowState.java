package com.involutionhell.backend.rag.indexing.workflow;

import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.model.RagIndexJobStatus;
import com.involutionhell.backend.rag.indexing.model.RagIndexStage;

/**
 * 索引工作流状态。
 */
public enum IndexWorkflowState {
    NEW,
    QUEUED,
    DISPATCHING,
    PREPARING,
    CHUNKING,
    SAVE_CHUNKS,
    VECTOR_INDEXING,
    COMMIT_INDEX,
    COMPLETED,
    FAILED,
    SKIPPED;

    /**
     * 根据当前 job 记录恢复工作流状态。
     */
    public static IndexWorkflowState from(RagIndexJobRecord record) {
        if (record == null) {
            return NEW;
        }
        if (RagIndexJobStatus.FAILED.name().equals(record.status())) {
            return FAILED;
        }
        if (RagIndexJobStatus.SKIPPED.name().equals(record.status())) {
            return SKIPPED;
        }
        if (RagIndexJobStatus.SUCCEEDED.name().equals(record.status())) {
            return COMPLETED;
        }
        if (record.stage() == null || record.stage().isBlank()) {
            return QUEUED;
        }
        if (FAILED.name().equals(record.stage())) {
            return FAILED;
        }
        return switch (RagIndexStage.valueOf(record.stage())) {
            case QUEUED -> QUEUED;
            case DISPATCHING -> DISPATCHING;
            case PREPARING -> PREPARING;
            case CHUNKING -> CHUNKING;
            case SAVE_CHUNKS -> SAVE_CHUNKS;
            case VECTOR_INDEXING -> VECTOR_INDEXING;
            case COMMIT_INDEX -> COMMIT_INDEX;
            case COMPLETED -> COMPLETED;
            case SKIPPED -> SKIPPED;
        };
    }

    /**
     * 是否为终态。
     */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == SKIPPED;
    }
}
