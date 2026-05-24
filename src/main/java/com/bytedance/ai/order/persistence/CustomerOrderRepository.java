package com.bytedance.ai.order.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CustomerOrderRepository {

    CustomerOrderRecord save(
            String cartId,
            String userId,
            String conversationId,
            String currency,
            BigDecimal subtotalAmount,
            int itemCount,
            Long deliveryAddressId,
            Map<String, Object> deliveryAddress,
            List<Map<String, Object>> priceChanges
    );

    Optional<CustomerOrderRecord> findByOrderId(String orderId);
}
