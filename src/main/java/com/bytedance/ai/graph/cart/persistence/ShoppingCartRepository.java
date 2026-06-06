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

    /**
     * 基于乐观锁推进购物车状态：仅当当前版本等于 {@code expectedVersion} 时才更新并自增版本。
     * 命中 0 行说明已被其它事务修改，应抛出并发冲突异常。
     *
     * <p>该方法是每个购物车写操作的首个状态变更，成功后即持有该行写锁，
     * 同事务内后续的 {@link #updateTotals}/{@link #updateShippingAddress} 因此无需再带版本校验。
     */
    void updateState(Long id, long expectedVersion, CartState state);

    void updateTotals(Long id, BigDecimal subtotalAmount, int itemCount);

    void updateShippingAddress(Long id, Map<String, Object> shippingAddress);
}
