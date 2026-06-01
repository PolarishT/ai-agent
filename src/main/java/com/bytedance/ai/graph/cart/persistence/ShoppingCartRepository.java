package com.bytedance.ai.graph.cart.persistence;

import com.bytedance.ai.graph.cart.api.CartState;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 购物车仓储端口，隔离领域逻辑与底层持久化实现。
 */
public interface ShoppingCartRepository {

    ShoppingCartRecord create(String userId, String conversationId);

    Optional<ShoppingCartRecord> findLatestActive(String userId, String conversationId);

    Optional<ShoppingCartRecord> findLatestActiveWithItemsByUser(String userId);

    Optional<ShoppingCartRecord> findById(Long id);

    void updateState(Long id, CartState state);

    void updateTotals(Long id, BigDecimal subtotalAmount, int itemCount);

    void updateShippingAddress(Long id, Map<String, Object> shippingAddress);
}
