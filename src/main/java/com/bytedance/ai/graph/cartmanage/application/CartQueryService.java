package com.bytedance.ai.graph.cartmanage.application;

import com.bytedance.ai.graph.cart.api.CartView;

/**
 * Read-side API consumed by cart_manage_workflow. Implementations are expected to be a thin
 * adapter over the cart bounded-context's {@code CartQueryFacade}.
 */
public interface CartQueryService {

    /**
     * Load the user's currently active cart for the given conversation.
     *
     * @return cart snapshot; if no active cart exists an empty cart should be returned (never null)
     */
    CartView getUserCart(String userId, String conversationId);
}
