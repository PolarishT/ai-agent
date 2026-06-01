package com.bytedance.ai.graph.order.api;

import java.util.Map;

/**
 * 订单门面接口，定义对外可调用的业务能力边界。
 */
public interface OrderCommandFacade {

    PlaceOrderResult placeOrder(String userId, String conversationId, Map<String, Object> address, boolean confirmPriceChange);
}
