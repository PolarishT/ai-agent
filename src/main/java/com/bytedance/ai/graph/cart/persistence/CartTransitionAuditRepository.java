package com.bytedance.ai.graph.cart.persistence;

import java.util.Map;

/**
 * 购物车仓储端口，隔离领域逻辑与底层持久化实现。
 */
public interface CartTransitionAuditRepository {

    void save(
            Long cartId,
            String businessCartId,
            String fromState,
            String toState,
            String event,
            String triggeredBy,
            boolean success,
            String failureReason,
            String errorMessage,
            Map<String, Object> metadata
    );
}
