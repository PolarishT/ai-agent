package com.bytedance.ai.graph.order.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单仓储端口，隔离领域逻辑与底层持久化实现。
 */
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
