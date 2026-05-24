package com.bytedance.ai.order.api;

import java.math.BigDecimal;

public record OrderItemView(
        Long itemId,
        Long spuId,
        String externalRef,
        String title,
        String brand,
        String imageUrl,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineAmount
) {
}
