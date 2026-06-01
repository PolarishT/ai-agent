package com.bytedance.ai.document.spi;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 提供给 indexing 模块的文档索引协作契约。
 *
 * <p>这个接口是 document 模块对 indexing 暴露的受控能力面。它只表达“索引流程需要
 * 文档模块提供什么能力”，不暴露文档模块内部 repository、表结构或 JDBC 实现细节。
 */
public interface DocumentIndexingSpi {

    /**
     * 按文档主键读取索引所需的文档快照。
     *
     * @param id 文档主键
     * @return 文档存在时返回索引视图，否则返回空
     */
    Optional<DocumentIndexingView> findById(Long id);

    /**
     * 查询在指定时间前仍处于 PENDING 的文档，用于补偿恢复长时间未被消费的索引请求。
     *
     * @param cutoff 截止时间，早于该时间仍待处理的文档会被返回
     * @param limit  返回上限
     * @return 待补偿的 PENDING 文档列表
     */
    List<DocumentIndexingView> findPendingBefore(OffsetDateTime cutoff, int limit);

    /**
     * 查询在指定时间前仍处于 PROCESSING 的文档，用于发现处理超时或异常中断的索引任务。
     *
     * @param cutoff 截止时间，早于该时间仍在处理中的文档会被返回
     * @param limit  返回上限
     * @return 待补偿的 PROCESSING 文档列表
     */
    List<DocumentIndexingView> findProcessingBefore(OffsetDateTime cutoff, int limit);

    /**
     * 查询在指定时间前仍处于 FAILED 的文档，用于按恢复策略重新排队或进入终态处理。
     *
     * @param cutoff 截止时间，早于该时间仍失败的文档会被返回
     * @param limit  返回上限
     * @return 待恢复的 FAILED 文档列表
     */
    List<DocumentIndexingView> findFailedBefore(OffsetDateTime cutoff, int limit);

    /**
     * 查询在指定时间前仍处于 DELETING 的文档，用于补偿完成切片、向量和主记录清理。
     *
     * @param cutoff 截止时间，早于该时间仍待删除的文档会被返回
     * @param limit  返回上限
     * @return 待清理的 DELETING 文档列表
     */
    List<DocumentIndexingView> findDeletingBefore(OffsetDateTime cutoff, int limit);

    /**
     * 将文档重新标记为 PENDING，清理上次尝试痕迹后等待再次索引。
     *
     * @param id 文档主键
     */
    void markPending(Long id);

    /**
     * 将文档重新排队，并记录本次补偿或重试原因。
     *
     * @param id   文档主键
     * @param note 重新排队原因说明
     */
    void requeue(Long id, String note);

    /**
     * 将文档标记为 PROCESSING，表示索引执行器已开始处理该文档。
     *
     * @param id 文档主键
     */
    void markProcessing(Long id);

    /**
     * 将文档标记为 INDEXED，并更新当前生效的索引 generation 与切片数量。
     *
     * @param id                文档主键
     * @param indexedGeneration 本次成功索引后生效的 generation
     * @param chunkCount        本次生成的切片数量
     * @param indexedAt         索引成功时间
     */
    void markIndexed(Long id, Long indexedGeneration, int chunkCount, OffsetDateTime indexedAt);

    /**
     * 将文档标记为 FAILED，并记录最近一次失败原因。
     *
     * @param id           文档主键
     * @param errorMessage 失败原因摘要
     */
    void markFailed(Long id, String errorMessage);

    /**
     * 将文档标记为 DELETING，并记录删除流程的受理或补偿说明。
     *
     * @param id   文档主键
     * @param note 删除状态说明
     */
    void markDeleting(Long id, String note);

    /**
     * 物理删除文档及其索引相关子数据。
     *
     * @param id 文档主键
     */
    void deleteById(Long id);
}
