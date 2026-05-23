package com.bytedance.ai.catalog.persistence;

import java.time.OffsetDateTime;

/**
 * catalog_attribute_outbox 表的记录模型。
 *
 * @param id            主键
 * @param spuId         所属 SPU id
 * @param externalRef   SPU 业务编号（冗余存储，方便 dispatcher 不查 SPU 主表也能构造消息体）
 * @param payloadJson   传给消费端的 JSON 负载（含触发来源 / 上次错误等可选元数据）
 * @param status        outbox 状态，详见 {@link CatalogAttributeOutboxStatus}
 * @param attemptCount  累计投递尝试次数（含重置后重试）
 * @param lastError     最近一次失败信息
 * @param nextSendAfter 下一次允许投递的最早时间；NULL 视为立即可投
 * @param messageId     RocketMQ 投递成功后由 producer 返回的消息 ID（用于消费端确认）
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record CatalogAttributeOutboxRecord(
        Long id,
        Long spuId,
        String externalRef,
        String payloadJson,
        String status,
        Integer attemptCount,
        String lastError,
        OffsetDateTime nextSendAfter,
        String messageId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
