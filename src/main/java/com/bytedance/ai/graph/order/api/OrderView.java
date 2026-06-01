package com.bytedance.ai.graph.order.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 订单对外只读视图。
 */
public record OrderView(
        String orderId,
        String cartId,
        String userId,
        String conversationId,
        String status,
        String currency,
        BigDecimal subtotalAmount,
        Integer itemCount,
        DeliveryAddressView deliveryAddress,
        List<PriceChangeView> priceChanges,
        List<OrderItemView> items,
        OffsetDateTime placedAt
) {
}
