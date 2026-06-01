package com.bytedance.ai.graph.ordermanage.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record MockOrderRecord(
        Long id,
        String orderNo,
        String userId,
        String conversationId,
        Map<String, Object> items,
        Map<String, Object> address,
        BigDecimal totalAmount,
        String status,
        LocalDateTime createdAt
) {
}
