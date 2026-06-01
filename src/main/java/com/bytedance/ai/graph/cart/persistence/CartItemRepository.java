package com.bytedance.ai.graph.cart.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 购物车仓储端口，隔离领域逻辑与底层持久化实现。
 */
public interface CartItemRepository {

    CartItemRecord upsertActive(
            Long cartId,
            Long spuId,
            String externalRef,
            String title,
            String brand,
            String imageUrl,
            int quantity,
            BigDecimal unitPrice,
            Integer stockSnapshot
    );

    Optional<CartItemRecord> findActive(Long cartId, Long itemId, Long spuId, String externalRef);

    List<CartItemRecord> findActiveByCartId(Long cartId);

    void updateQuantity(Long itemId, int quantity);

    void markRemoved(Long itemId);
}
