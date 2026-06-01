package com.bytedance.ai.graph.cart.api;

/**
 * 购物车门面接口，定义对外可调用的业务能力边界。
 */
public interface CartQueryFacade {

    CartView getActiveCart(String userId, String conversationId);
}
