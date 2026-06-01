package com.bytedance.ai.graph.ordermanage.persistence;

import java.math.BigDecimal;
import java.util.Map;

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
