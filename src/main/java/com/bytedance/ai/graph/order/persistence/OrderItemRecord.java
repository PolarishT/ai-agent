package com.bytedance.ai.graph.order.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 订单持久化记录模型。
 */
public record OrderItemRecord(
        Long id,
        Long orderId,
        Long spuId,
        String externalRef,
        String title,
        String brand,
        String imageUrl,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineAmount,
        OffsetDateTime createdAt
) {
}
