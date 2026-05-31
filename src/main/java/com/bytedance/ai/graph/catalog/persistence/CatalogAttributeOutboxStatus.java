package com.bytedance.ai.graph.catalog.persistence;

/**
 * catalog_attribute_outbox 表的状态机。
 *
 * <pre>
 *  NEW ─┬─ dispatcher claim ─▶ SENDING ─┬─ markSent ─▶ SENT
 *           │                                │
 *           │                                └─ markFailed ─▶ FAILED ──▶ NEW (重试)
 *           └─ (短路重入：enqueue 命中已存在的 NEW 时直接复用，不新增行)
 * </pre>
 */
public enum CatalogAttributeOutboxStatus {
    /** 已入队，等待 dispatcher 投递。 */
    NEW,
    /** dispatcher 已声明发送权，正在调 RocketMQ producer。 */
    SENDING,
    /** RocketMQ 投递成功。 */
    SENT,
    /** 投递异常；dispatcher 会按 next_attempt_at 退避后再次进入 NEW。 */
    FAILED,
    /** 消费端已处理。 */
    CONSUMED
}
