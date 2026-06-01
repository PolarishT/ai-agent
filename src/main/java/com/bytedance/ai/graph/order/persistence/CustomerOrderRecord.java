package com.bytedance.ai.graph.order.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单持久化记录模型。
 */
public record CustomerOrderRecord(
        Long id,
        String orderId,
        String cartId,
        String userId,
        String conversationId,
        String status,
        String currency,
        BigDecimal subtotalAmount,
        Integer itemCount,
        Long deliveryAddressId,
        Map<String, Object> deliveryAddress,
        List<Map<String, Object>> priceChanges,
        OffsetDateTime placedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
