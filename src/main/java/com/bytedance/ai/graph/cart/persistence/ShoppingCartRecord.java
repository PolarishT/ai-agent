package com.bytedance.ai.graph.cart.persistence;

import com.bytedance.ai.graph.cart.api.CartState;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 购物车持久化记录模型。
 */
public record ShoppingCartRecord(
        Long id,
        String cartId,
        String userId,
        String conversationId,
        CartState state,
        String currency,
        BigDecimal subtotalAmount,
        Integer itemCount,
        Map<String, Object> shippingAddress,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
