package com.bytedance.ai.cart.api;

/**
 * Public cart lifecycle state exposed in {@link CartView}.
 *
 * <p>The state machine implementation is internal to the cart module, but the current state is
 * part of the cart read model returned to other modules and clients.
 */
public enum CartState {
    IDLE,
    ITEM_PROPOSED,
    IN_CART,
    CHECKING_OUT,
    PLACED,
    CANCELLED
}
