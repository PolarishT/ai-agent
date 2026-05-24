package com.bytedance.ai.cart.api;

public interface CartQueryFacade {

    CartView getActiveCart(String userId, String conversationId);
}
