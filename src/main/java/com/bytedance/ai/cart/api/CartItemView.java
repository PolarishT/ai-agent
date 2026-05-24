package com.bytedance.ai.cart.api;

import java.math.BigDecimal;

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
