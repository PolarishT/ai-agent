package com.bytedance.ai.graph.order.persistence;

import java.util.Optional;

/**
 * 订单仓储端口，隔离领域逻辑与底层持久化实现。
 */
public interface DeliveryAddressRepository {

    DeliveryAddressRecord save(String userId, java.util.Map<String, Object> address, boolean isDefault);

    DeliveryAddressRecord saveDefaultIfAbsent(String userId);

    Optional<DeliveryAddressRecord> findDefaultByUserId(String userId);
}
