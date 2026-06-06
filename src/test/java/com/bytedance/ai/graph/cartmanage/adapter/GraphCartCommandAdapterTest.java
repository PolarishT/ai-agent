package com.bytedance.ai.graph.cartmanage.adapter;

import com.bytedance.ai.graph.cart.api.CartCommandFacade;
import com.bytedance.ai.graph.cart.api.CartQueryFacade;
import com.bytedance.ai.graph.cart.api.CartView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphCartCommandAdapterTest {

    @Test
    void addItemIgnoresCandidateCachedPrice() {
        StubCartCommandFacade commandFacade = new StubCartCommandFacade();
        GraphCartCommandAdapter adapter = new GraphCartCommandAdapter(commandFacade, (userId, conversationId) -> null);

        adapter.addItem("user-1", "conversation-1", "101", "4", 2, new BigDecimal("199.00"));

        assertThat(commandFacade.expectedUnitPrice).isNull();
        assertThat(commandFacade.spuId).isEqualTo(101L);
        assertThat(commandFacade.skuId).isEqualTo(4L);
        assertThat(commandFacade.quantity).isEqualTo(2);
    }

    private static final class StubCartCommandFacade implements CartCommandFacade {
        Long spuId;
        Long skuId;
        Integer quantity;
        BigDecimal expectedUnitPrice;

        @Override
        public CartView proposeItem(String userId, String conversationId, Long spuId, String externalRef, Integer quantity) {
            return null;
        }

        @Override
        public CartView addItem(String userId, String conversationId, Long spuId, Long skuId, String externalRef,
                                Integer quantity, BigDecimal expectedUnitPrice) {
            this.spuId = spuId;
            this.skuId = skuId;
            this.quantity = quantity;
            this.expectedUnitPrice = expectedUnitPrice;
            return null;
        }

        @Override
        public CartView removeItem(String userId, String conversationId, Long itemId, Long spuId, String externalRef) {
            return null;
        }

        @Override
        public CartView updateQuantity(String userId, String conversationId, Long itemId, Long spuId,
                                       String externalRef, Integer quantity) {
            return null;
        }

        @Override
        public CartView checkout(String userId, String conversationId, Map<String, Object> shippingAddress) {
            return null;
        }

        @Override
        public CartView cancel(String userId, String conversationId) {
            return null;
        }
    }
}
