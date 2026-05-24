package com.bytedance.ai.order.persistence;

import java.time.OffsetDateTime;

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
