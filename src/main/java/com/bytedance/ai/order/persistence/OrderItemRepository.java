package com.bytedance.ai.order.persistence;

import java.math.BigDecimal;
import java.util.List;

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
