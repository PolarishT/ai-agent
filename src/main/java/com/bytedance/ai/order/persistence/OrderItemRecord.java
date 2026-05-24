package com.bytedance.ai.order.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
