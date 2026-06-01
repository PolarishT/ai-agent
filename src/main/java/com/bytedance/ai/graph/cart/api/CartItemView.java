package com.bytedance.ai.graph.cart.api;

import java.math.BigDecimal;

/**
 * 购物车对外只读视图。
 */
public record CartItemView(
        Long itemId,
        Long spuId,
        String externalRef,
        String title,
        String brand,
        String imageUrl,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineAmount,
        Integer stockSnapshot
) {
}
