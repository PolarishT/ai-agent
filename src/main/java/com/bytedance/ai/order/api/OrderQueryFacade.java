package com.bytedance.ai.order.api;

public interface OrderQueryFacade {

    OrderView getOrder(String orderId);
}
