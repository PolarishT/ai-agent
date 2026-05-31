package com.bytedance.ai.graph.catalog.messaging;

/**
 * catalog 抽属性消息体。
 *
 * <p>消费端从中拿 {@code productId}（主键）即可，其它属性靠 catalog_product 实时读。
 *
 * @param productId    所属 Product id（必填）
 * @param triggeredBy  触发来源，例如 "import" / "manual-retry"
 * @param enqueuedAtMs 入队时间戳（毫秒），用于消费端度量"消息滞留时长"
 */
public record CatalogAttributeMessagePayload(
        Long productId,
        String triggeredBy,
        long enqueuedAtMs
) {
    @Deprecated
    public Long spuId() {
        return productId;
    }
}
