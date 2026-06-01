package com.bytedance.ai.graph.cartmanage.application;

import com.bytedance.ai.graph.cartmanage.CartMutationResult;

/**
 * Write-side API consumed by cart_manage_workflow. Implementations adapt the spec-shaped calls
 * to the cart bounded-context's {@code CartCommandFacade}.
 *
 * <p>The workflow node never lets the LLM decide a {@code cartItemId}; this id is always resolved
 * server-side from the current cart snapshot before invoking these methods.
 */
public interface CartCommandService {

    CartMutationResult addItem(
            String userId,
            String conversationId,
            String productId,
            String skuId,
            int quantity,
            java.math.BigDecimal expectedUnitPrice
    );

    CartMutationResult removeItem(String userId, String conversationId, String cartItemId);

    CartMutationResult updateQuantity(String userId, String conversationId, String cartItemId, int quantity);

    /**
     * Clear all items from the user's active cart.
     *
     * <p>NOTE: cart_manage_workflow does NOT call this directly. CLEAR_CART always returns
     * WAITING_CONFIRMATION and a follow-up confirm action is expected to invoke this.
     */
    CartMutationResult clearCart(String userId, String conversationId);
}
