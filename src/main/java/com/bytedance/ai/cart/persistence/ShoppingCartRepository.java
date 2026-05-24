package com.bytedance.ai.cart.persistence;

import com.bytedance.ai.cart.api.CartState;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public interface ShoppingCartRepository {

    ShoppingCartRecord create(String userId, String conversationId);

    Optional<ShoppingCartRecord> findLatestActive(String userId, String conversationId);

    Optional<ShoppingCartRecord> findById(Long id);

    void updateState(Long id, CartState state);

    void updateTotals(Long id, BigDecimal subtotalAmount, int itemCount);

    void updateShippingAddress(Long id, Map<String, Object> shippingAddress);
}
