package com.bytedance.ai.graph.ordermanage.persistence;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 订单管理仓储端口，隔离领域逻辑与底层持久化实现。
 */
public interface MockOrderRepository {

    MockOrderRecord create(
            String orderNo,
            String userId,
            String conversationId,
            Map<String, Object> items,
            Map<String, Object> address,
            BigDecimal totalAmount
    );
}
