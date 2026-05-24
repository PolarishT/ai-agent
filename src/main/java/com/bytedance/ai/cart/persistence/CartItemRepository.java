package com.bytedance.ai.cart.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

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
