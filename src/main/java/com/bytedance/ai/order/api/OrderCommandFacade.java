package com.bytedance.ai.order.api;

import java.util.Map;

public interface OrderCommandFacade {

    PlaceOrderResult placeOrder(String userId, String conversationId, Map<String, Object> address, boolean confirmPriceChange);
}
