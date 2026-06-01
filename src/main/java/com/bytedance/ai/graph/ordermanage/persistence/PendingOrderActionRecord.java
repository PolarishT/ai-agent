package com.bytedance.ai.graph.ordermanage.persistence;

import com.bytedance.ai.graph.ordermanage.OrderManageStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 订单管理持久化记录模型。
 */
public record PendingOrderActionRecord(
        Long id,
        String userId,
        String conversationId,
        Map<String, Object> cartSnapshot,
        String cartSnapshotHash,
        Map<String, Object> addressSnapshot,
        BigDecimal amountSnapshot,
        OrderManageStatus status,
        String failReason,
        String orderNo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime expireAt
) {
}
