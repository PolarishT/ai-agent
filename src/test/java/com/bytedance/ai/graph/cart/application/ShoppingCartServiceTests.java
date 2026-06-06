package com.bytedance.ai.graph.cart.application;

import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.cart.persistence.CartItemRecord;
import com.bytedance.ai.graph.cart.persistence.CartItemRepository;
import com.bytedance.ai.graph.cart.persistence.ShoppingCartRecord;
import com.bytedance.ai.graph.cart.persistence.ShoppingCartRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ShoppingCartServiceTests {

    @Test
    void getActiveCartFallsBackToLatestNonEmptyUserCartWhenConversationCartIsEmpty() {
        ShoppingCartRecord oldNonEmptyCart = cart(1L, "cart-old", "u-1", "conversation-old", CartState.IN_CART, 1);
        ShoppingCartRecord newEmptyCart = cart(2L, "cart-new", "u-1", "conversation-new", CartState.IDLE, 0);
        StubCartRepository cartRepository = new StubCartRepository();
        cartRepository.byConversation.put("u-1/conversation-new", newEmptyCart);
        cartRepository.latestNonEmptyByUser.put("u-1", oldNonEmptyCart);
        StubCartItemRepository itemRepository = new StubCartItemRepository();
        itemRepository.itemsByCart.put(oldNonEmptyCart.id(), List.of(item(oldNonEmptyCart.id())));

        ShoppingCartService service = new ShoppingCartService(
                cartRepository,
                itemRepository,
                null,
                null,
                null
        );

        CartView cart = service.getActiveCart("u-1", "conversation-new");

        assertThat(cart.cartId()).isEqualTo("cart-old");
        assertThat(cart.conversationId()).isEqualTo("conversation-old");
        assertThat(cart.items()).hasSize(1);
    }

    private static ShoppingCartRecord cart(
            Long id,
            String cartId,
            String userId,
            String conversationId,
            CartState state,
            int itemCount
    ) {
        return new ShoppingCartRecord(
                id,
                cartId,
                userId,
                conversationId,
                state,
                "CNY",
                new BigDecimal("99.00"),
                itemCount,
                Map.of(),
                0L,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private static CartItemRecord item(Long cartId) {
        return new CartItemRecord(
                10L,
                cartId,
                101L,
                null,
                "SPU-101",
                "轻量通勤双肩包",
                "brand",
                null,
                1,
                new BigDecimal("99.00"),
                10,
                "ACTIVE",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private static final class StubCartRepository implements ShoppingCartRepository {
        final Map<String, ShoppingCartRecord> byConversation = new HashMap<>();
        final Map<String, ShoppingCartRecord> latestNonEmptyByUser = new HashMap<>();

        @Override
        public ShoppingCartRecord create(String userId, String conversationId) {
            return cart(99L, "cart-created", userId, conversationId, CartState.IDLE, 0);
        }

        @Override
        public Optional<ShoppingCartRecord> findLatestActive(String userId, String conversationId) {
            return Optional.ofNullable(byConversation.get(userId + "/" + conversationId));
        }

        @Override
        public Optional<ShoppingCartRecord> findLatestActiveWithItemsByUser(String userId) {
            return Optional.ofNullable(latestNonEmptyByUser.get(userId));
        }

        @Override
        public Optional<ShoppingCartRecord> findById(Long id) {
            return byConversation.values().stream()
                    .filter(cart -> cart.id().equals(id))
                    .findFirst();
        }

        @Override
        public void updateState(Long id, long expectedVersion, CartState state) {
        }

        @Override
        public void updateTotals(Long id, BigDecimal subtotalAmount, int itemCount) {
        }

        @Override
        public void updateShippingAddress(Long id, Map<String, Object> shippingAddress) {
        }
    }

    private static final class StubCartItemRepository implements CartItemRepository {
        final Map<Long, List<CartItemRecord>> itemsByCart = new HashMap<>();

        @Override
        public CartItemRecord upsertActive(Long cartId, Long spuId, Long skuId, String externalRef, String title, String brand,
                                           String imageUrl, int quantity, BigDecimal unitPrice, Integer stockSnapshot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CartItemRecord> findActive(Long cartId, Long itemId, Long spuId, String externalRef) {
            return findActiveByCartId(cartId).stream()
                    .filter(item -> itemId == null || item.id().equals(itemId))
                    .findFirst();
        }

        @Override
        public List<CartItemRecord> findActiveByCartId(Long cartId) {
            return itemsByCart.getOrDefault(cartId, List.of());
        }

        @Override
        public void updateQuantity(Long itemId, int quantity) {
        }

        @Override
        public void markRemoved(Long itemId) {
        }
    }
}
