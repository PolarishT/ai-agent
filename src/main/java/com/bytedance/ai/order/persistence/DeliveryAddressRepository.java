package com.bytedance.ai.order.persistence;

import java.util.Optional;

public interface DeliveryAddressRepository {

    DeliveryAddressRecord save(String userId, java.util.Map<String, Object> address, boolean isDefault);

    DeliveryAddressRecord saveDefaultIfAbsent(String userId);

    Optional<DeliveryAddressRecord> findDefaultByUserId(String userId);
}
