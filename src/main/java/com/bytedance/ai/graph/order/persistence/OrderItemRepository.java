package com.bytedance.ai.graph.order.persistence;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单仓储端口，隔离领域逻辑与底层持久化实现。
 */
public interface OrderItemRepository {

    void save(
            Long orderId,
            Long spuId,
            String externalRef,
            String title,
            String brand,
            String imageUrl,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineAmount
    );

    List<OrderItemRecord> findByOrderId(Long orderId);
}
