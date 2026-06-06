package com.bytedance.ai.graph.order.api;

import java.math.BigDecimal;

/**
 * 订单对外只读视图。
 */
public record OrderItemView(
        Long itemId,
        Long spuId,
        Long skuId,
        String externalRef,
        String title,
        String brand,
        String imageUrl,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineAmount
) {
}
