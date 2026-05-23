package com.bytedance.ai.catalog.persistence;

/**
 * catalog_attribute_outbox 表的状态机。
 *
 * <pre>
 *  PENDING ─┬─ dispatcher claim ─▶ SENDING ─┬─ markSent ─▶ SENT
 *           │                                │
 *           │                                └─ markFailed ─▶ FAILED ──▶ PENDING (重试)
 *           └─ (短路重入：enqueue 命中已存在的 PENDING 时直接复用，不新增行)
 * </pre>
 */
public enum CatalogAttributeOutboxStatus {
    /** 已入队，等待 dispatcher 投递。 */
    PENDING,
    /** dispatcher 已声明发送权，正在调 RocketMQ producer。 */
    SENDING,
    /** RocketMQ 投递成功；消费端处理结果由 catalog_spu.attributes_status 自身状态机记录，无需在 outbox 回写。 */
    SENT,
    /** 投递异常；dispatcher 会按 next_send_after 退避后再次进入 PENDING。 */
    FAILED
}
