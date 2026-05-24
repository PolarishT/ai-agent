package com.bytedance.ai.cart.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CartItemRecord(
        Long id,
        Long cartId,
        Long spuId,
        String externalRef,
        String title,
        String brand,
        String imageUrl,
        Integer quantity,
        BigDecimal unitPrice,
        Integer stockSnapshot,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
