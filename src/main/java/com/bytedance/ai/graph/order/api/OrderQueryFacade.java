package com.bytedance.ai.graph.order.api;

/**
 * 订单门面接口，定义对外可调用的业务能力边界。
 */
public interface OrderQueryFacade {

    OrderView getOrder(String orderId);
}
