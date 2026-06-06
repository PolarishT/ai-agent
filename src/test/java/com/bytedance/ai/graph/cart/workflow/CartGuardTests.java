package com.bytedance.ai.graph.cart.workflow;

import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartGuardTests {

    @Test
    void sameNumericPriceWithDifferentScaleDoesNotTriggerPriceChanged() {
        CartGuard guard = new CartGuard(new StubCatalog(new BigDecimal("199")));
        CartCommand command = new CartCommand(
                "user-1", "conversation-1", 101L, null, null, 1,
                new BigDecimal("199.00"), Map.of(), "test", null, null, Map.of());

        assertThatCode(() -> guard.validate(CartEvent.CONFIRM_ADD, com.bytedance.ai.graph.cart.api.CartState.IN_CART, command))
                .doesNotThrowAnyException();
    }

    @Test
    void differentNumericPriceTriggersPriceChanged() {
        CartGuard guard = new CartGuard(new StubCatalog(new BigDecimal("199")));
        CartCommand command = new CartCommand(
                "user-1", "conversation-1", 101L, null, null, 1,
                new BigDecimal("209.00"), Map.of(), "test", null, null, Map.of());

        assertThatThrownBy(() -> guard.validate(CartEvent.CONFIRM_ADD, com.bytedance.ai.graph.cart.api.CartState.IN_CART, command))
                .isInstanceOf(CartWorkflowException.class)
                .hasMessageContaining("商品价格已变化");
    }

    @Test
    void skuPriceAndStockAreUsedWhenSkuIdIsPresent() {
        CartGuard guard = new CartGuard(new StubCatalog(
                new BigDecimal("199"),
                List.of(new CatalogSkuView(4L, "4", Map.of("color", "black"), new BigDecimal("188.00"), 2, "ACTIVE"))
        ));
        CartCommand command = new CartCommand(
                "user-1", "conversation-1", 101L, 4L, null, 2,
                new BigDecimal("188.0"), Map.of(), "test", null, null, Map.of());

        assertThatCode(() -> guard.validate(CartEvent.CONFIRM_ADD, com.bytedance.ai.graph.cart.api.CartState.IN_CART, command))
                .doesNotThrowAnyException();
    }

    @Test
    void skuStockInsufficientRejectsCartMutation() {
        CartGuard guard = new CartGuard(new StubCatalog(
                new BigDecimal("199"),
                List.of(new CatalogSkuView(4L, "4", Map.of("color", "black"), new BigDecimal("188.00"), 1, "ACTIVE"))
        ));
        CartCommand command = new CartCommand(
                "user-1", "conversation-1", 101L, 4L, null, 2,
                null, Map.of(), "test", null, null, Map.of());

        assertThatThrownBy(() -> guard.validate(CartEvent.CONFIRM_ADD, com.bytedance.ai.graph.cart.api.CartState.IN_CART, command))
                .isInstanceOf(CartWorkflowException.class)
                .hasMessageContaining("SKU 库存不足");
    }

    private record StubCatalog(BigDecimal price, List<CatalogSkuView> skus) implements CatalogQueryFacade {

        private StubCatalog(BigDecimal price) {
            this(price, List.of());
        }

        @Override
        public CatalogProductView getProduct(Long productId) {
            return new CatalogProductView(
                    productId, "通勤包", "brand", "bags", null,
                    price, price, price, 10, "", "ACTIVE",
                    Map.of(), Map.of(), skus, OffsetDateTime.now(), OffsetDateTime.now());
        }

        @Override
        public List<CatalogSkuView> listSkus(Long productId) {
            return List.of();
        }
    }
}
