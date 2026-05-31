package com.bytedance.ai.graph.catalog.persistence;

import java.time.OffsetDateTime;

/**
 * catalog_attribute_outbox 表的记录模型。
 *
 * @param id            主键
 * @param productId     所属 Product id
 * @param eventType     事件类型
 * @param metadataJson  传给消费端的 JSON 元数据
 * @param status        outbox 状态，详见 {@link CatalogAttributeOutboxStatus}
 * @param attemptCount  累计投递尝试次数（含重置后重试）
 * @param lastError     最近一次失败信息
 * @param nextAttemptAt 下一次允许投递的最早时间；NULL 视为立即可投
 * @param messageId     RocketMQ 投递成功后由 producer 返回的消息 ID（用于消费端确认）
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record CatalogAttributeOutboxRecord(
        Long id,
        Long productId,
        String eventType,
        String metadataJson,
        String status,
        Integer attemptCount,
        String lastError,
        OffsetDateTime nextAttemptAt,
        String messageId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    @Deprecated
    public Long spuId() {
        return productId;
    }

    @Deprecated
    public String externalRef() {
        return String.valueOf(productId);
    }

    @Deprecated
    public String payloadJson() {
        return metadataJson;
    }

    @Deprecated
    public OffsetDateTime nextSendAfter() {
        return nextAttemptAt;
    }
}
