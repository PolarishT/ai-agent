package com.bytedance.ai.graph.cart.api;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 购物车门面接口，定义对外可调用的业务能力边界。
 */
public interface CartCommandFacade {

    CartView proposeItem(String userId, String conversationId, Long spuId, String externalRef, Integer quantity);

    CartView addItem(
            String userId,
            String conversationId,
            Long spuId,
            String externalRef,
            Integer quantity,
            BigDecimal expectedUnitPrice
    );

    CartView removeItem(String userId, String conversationId, Long itemId, Long spuId, String externalRef);

    CartView updateQuantity(String userId, String conversationId, Long itemId, Long spuId, String externalRef, Integer quantity);

    CartView checkout(String userId, String conversationId, Map<String, Object> shippingAddress);

    CartView cancel(String userId, String conversationId);
}
