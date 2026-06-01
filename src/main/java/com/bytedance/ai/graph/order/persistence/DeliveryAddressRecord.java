package com.bytedance.ai.graph.order.persistence;

import java.time.OffsetDateTime;

/**
 * 订单持久化记录模型。
 */
public record DeliveryAddressRecord(
        Long id,
        String userId,
        String receiverName,
        String phone,
        String province,
        String city,
        String district,
        String detail,
        String postalCode,
        Boolean isDefault,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
