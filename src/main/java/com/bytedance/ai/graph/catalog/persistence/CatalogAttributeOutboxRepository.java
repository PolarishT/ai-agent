package com.bytedance.ai.graph.catalog.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * catalog 抽属性 Outbox 仓储。
 */
public interface CatalogAttributeOutboxRepository {

    /**
     * 入队一条抽属性请求。重复 enqueue 同一个 productId 时复用既有 NEW/FAILED 行，
     * 不会产生重复投递。
     */
    void enqueue(Long productId, String eventType, String metadataJson);

    /**
     * 查询 dispatcher 当前应该认领的 outbox 行（NEW 或 FAILED 且 next_attempt_at 已到）。
     */
    List<CatalogAttributeOutboxRecord> findDispatchable(OffsetDateTime now, int limit);

    /**
     * 把指定 id 从 NEW/FAILED 原子地迁移到 SENDING，确保多实例 dispatcher 不会重复投递。
     *
     * @return 是否抢占成功
     */
    boolean markSending(Long id);

    /**
     * 投递成功，记录 RocketMQ 返回的 messageId。
     */
    void markSent(Long id, String messageId);

    /**
     * 投递失败：状态切回 FAILED，attempt_count++，并设置下次允许投递时间。
     */
    void markFailed(Long id, String errorMessage, OffsetDateTime nextSendAfter);

    /**
     * 长时间卡在 SENDING 的记录，供恢复任务接管（dispatcher 节点崩溃后释放锁）。
     */
    List<CatalogAttributeOutboxRecord> findStuckSendingBefore(OffsetDateTime cutoff, int limit);

    /**
     * 把卡住的 SENDING 行复位为 FAILED，等待 dispatcher 重新投递。
     */
    void resetForRetry(Long id, String errorMessage, OffsetDateTime nextSendAfter);

    /**
     * 查询 Product 最近一条 outbox 行，主要用于排障 / 测试断言。
     */
    Optional<CatalogAttributeOutboxRecord> findLatestByProductId(Long productId);

    @Deprecated
    default Optional<CatalogAttributeOutboxRecord> findLatestBySpuId(Long productId) {
        return findLatestByProductId(productId);
    }
}
