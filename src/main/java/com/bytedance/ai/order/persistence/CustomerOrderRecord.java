package com.bytedance.ai.order.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

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
