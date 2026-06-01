package com.bytedance.ai.graph.cart.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 购物车对外只读视图。
 */
public record CartView(
        String cartId,
        String userId,
        String conversationId,
        CartState state,
        String currency,
        BigDecimal subtotalAmount,
        Integer itemCount,
        Map<String, Object> shippingAddress,
        List<CartItemView> items
) {
}
