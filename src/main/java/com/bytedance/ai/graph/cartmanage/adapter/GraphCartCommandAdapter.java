package com.bytedance.ai.graph.cartmanage.adapter;

import com.bytedance.ai.graph.cart.api.CartCommandFacade;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartQueryFacade;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.cartmanage.application.CartCommandService;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;

/**
 * Adapter implementing the cart-manage workflow's write contract on top of {@link CartCommandFacade}.
 *
 * <p>The cart-manage workflow always passes the resolved {@code cartItemId} as the canonical Long
 * primary key (formatted as String). spuId / externalRef are passed as null because we already have
 * the precise itemId.
 */
@Service
public class GraphCartCommandAdapter implements CartCommandService {

    private static final Logger log = LoggerFactory.getLogger(GraphCartCommandAdapter.class);

    private final CartCommandFacade cartCommandFacade;
    private final CartQueryFacade cartQueryFacade;

    public GraphCartCommandAdapter(CartCommandFacade cartCommandFacade, CartQueryFacade cartQueryFacade) {
        this.cartCommandFacade = cartCommandFacade;
        this.cartQueryFacade = cartQueryFacade;
    }

    @Override
    public CartMutationResult addItem(
            String userId,
            String conversationId,
            String productId,
            String skuId,
            int quantity,
            BigDecimal expectedUnitPrice
    ) {
        Long spuId = parseItemId(productId);
        if (spuId == null) {
            return CartMutationResult.failure("PRODUCT_ID_INVALID",
                    "productId must be a numeric id: " + productId);
        }
        Long parsedSkuId = parseItemId(skuId);
        if (skuId != null && !skuId.isBlank() && parsedSkuId == null) {
            return CartMutationResult.failure("SKU_ID_INVALID",
                    "skuId must be a numeric id: " + skuId);
        }
        try {
            if (expectedUnitPrice != null) {
                log.atInfo()
                        .addKeyValue("event.name", "cart_manage.add_item.ignored_candidate_price")
                        .addKeyValue("cart.user_id", userId)
                        .addKeyValue("cart.product_id", productId)
                        .addKeyValue("cart.sku_id", skuId)
                        .addKeyValue("cart.expected_price", expectedUnitPrice)
                        .addKeyValue("cart.price_source", "CANDIDATE_CACHE")
                        .log("Ignoring candidate cached price for cart mutation; cart aggregate uses current catalog price");
            }
            CartView updated = cartCommandFacade.addItem(
                    userId, conversationId, spuId, parsedSkuId, null, quantity, null);
            return CartMutationResult.ok(updated);
        } catch (RuntimeException exception) {
            log.atWarn()
                    .addKeyValue("event.name", "cart_manage.add_item.business_failure")
                    .addKeyValue("cart.user_id", userId)
                    .addKeyValue("cart.product_id", productId)
                    .addKeyValue("cart.sku_id", skuId)
                    .addKeyValue("cart.quantity", quantity)
                    .setCause(exception)
                    .log("addItem rejected by cart aggregate: {}", exception.getMessage());
            return CartMutationResult.failure("CART_ADD_REJECTED", exception.getMessage());
        }
    }

    @Override
    public CartMutationResult removeItem(String userId, String conversationId, String cartItemId) {
        Long itemId = parseItemId(cartItemId);
        if (itemId == null) {
            return CartMutationResult.failure("CART_ITEM_ID_INVALID",
                    "cartItemId must be a numeric id: " + cartItemId);
        }
        try {
            CartView updated = cartCommandFacade.removeItem(userId, conversationId, itemId, null, null);
            return CartMutationResult.ok(updated);
        } catch (RuntimeException exception) {
            log.atWarn()
                    .addKeyValue("event.name", "cart_manage.remove_item.business_failure")
                    .addKeyValue("cart.user_id", userId)
                    .addKeyValue("cart.item_id", itemId)
                    .setCause(exception)
                    .log("removeItem rejected by cart aggregate: {}", exception.getMessage());
            return CartMutationResult.failure("CART_REMOVE_REJECTED", exception.getMessage());
        }
    }

    @Override
    public CartMutationResult updateQuantity(
            String userId, String conversationId, String cartItemId, int quantity
    ) {
        Long itemId = parseItemId(cartItemId);
        if (itemId == null) {
            return CartMutationResult.failure("CART_ITEM_ID_INVALID",
                    "cartItemId must be a numeric id: " + cartItemId);
        }
        try {
            CartView updated = cartCommandFacade.updateQuantity(
                    userId, conversationId, itemId, null, null, quantity);
            return CartMutationResult.ok(updated);
        } catch (RuntimeException exception) {
            log.atWarn()
                    .addKeyValue("event.name", "cart_manage.update_quantity.business_failure")
                    .addKeyValue("cart.user_id", userId)
                    .addKeyValue("cart.item_id", itemId)
                    .addKeyValue("cart.quantity", quantity)
                    .setCause(exception)
                    .log("updateQuantity rejected by cart aggregate: {}", exception.getMessage());
            return CartMutationResult.failure("CART_UPDATE_REJECTED", exception.getMessage());
        }
    }

    @Override
    public CartMutationResult clearCart(String userId, String conversationId) {
        try {
            CartView current = cartQueryFacade.getActiveCart(userId, conversationId);
            if (current == null || current.items() == null || current.items().isEmpty()) {
                return CartMutationResult.ok(current);
            }

            CartView updated = current;
            for (CartItemView item : current.items()) {
                if (item == null || item.itemId() == null) {
                    return CartMutationResult.failure("CART_ITEM_ID_MISSING",
                            "cart item id is missing while clearing cart");
                }
                updated = cartCommandFacade.removeItem(userId, conversationId, item.itemId(), null, null);
            }
            return CartMutationResult.ok(updated);
        } catch (RuntimeException exception) {
            log.atWarn()
                    .addKeyValue("event.name", "cart_manage.clear_cart.business_failure")
                    .addKeyValue("cart.user_id", userId)
                    .addKeyValue("cart.conversation_id", conversationId)
                    .setCause(exception)
                    .log("clearCart rejected by cart aggregate: {}", exception.getMessage());
            return CartMutationResult.failure("CART_CLEAR_REJECTED", exception.getMessage());
        }
    }

    private Long parseItemId(String cartItemId) {
        if (cartItemId == null || cartItemId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cartItemId.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
