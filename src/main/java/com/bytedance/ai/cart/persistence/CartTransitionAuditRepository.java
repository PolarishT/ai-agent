package com.bytedance.ai.cart.persistence;

import java.util.Map;

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
