package com.bytedance.ai.indexing.persistence;

import com.bytedance.ai.indexing.model.RagIndexStage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 离线索引作业仓储。
 */
public interface RagIndexJobRepository {

    /**
     * 创建或重置一条待分发的作业记录。
     */
    void queue(Long documentId, String contentSha256);

    /**
     * 绑定 MQ messageId，便于把消息投递和作业状态串起来追踪。
     */
    void attachMessageId(Long documentId, String contentSha256, String messageId);

    /**
     * 记录最近一次工作流事件并递增状态版本。
     */
    void annotateEvent(Long documentId, String contentSha256, String event);

    /**
     * 开启一次新的索引尝试，并记录目标 generation。
     */
    int startAttempt(Long documentId, String contentSha256, RagIndexStage fromStage, Long targetGeneration);

    /**
     * 推进作业当前阶段，不改变最终状态。
     */
    int updateStage(Long documentId, String contentSha256, RagIndexStage fromStage, RagIndexStage toStage);

    /**
     * 标记作业成功完成。
     */
    int markSucceeded(Long documentId, String contentSha256, RagIndexStage fromStage, Long targetGeneration);

    /**
     * 标记作业不可恢复地失败。
     */
    int markFailed(Long documentId, String contentSha256, RagIndexStage fromStage, RagIndexStage failureStage, String errorMessage);

    /**
     * 标记本次消息被跳过，例如文档版本已过时或主记录不存在。
     */
    int markSkipped(Long documentId, String contentSha256, RagIndexStage fromStage, String reason);

    /**
     * 查询文档某个内容版本对应的作业状态。
     */
    Optional<RagIndexJobRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256);

    /**
     * 删除指定文档的全部作业记录。
     */
    void deleteByDocumentId(Long documentId);

    // 查找所有非终态且超过指定时间未更新的任务
    List<RagIndexJobRecord> findStaleJobs(OffsetDateTime updatedBefore, int limit);
}
