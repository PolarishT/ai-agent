package com.bytedance.ai.cart.api;

import java.math.BigDecimal;
import java.util.Map;

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
