package com.bytedance.ai.graph.order.api;

import java.math.BigDecimal;

/**
 * 订单对外只读视图。
 */
public record PriceChangeView(
        Long spuId,
        String externalRef,
        String title,
        BigDecimal cartUnitPrice,
        BigDecimal currentUnitPrice
) {
}
