package com.bytedance.ai.graph.cart.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 购物车持久化记录模型。
 */
public record CartItemRecord(
        Long id,
        Long cartId,
        Long spuId,
        Long skuId,
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
