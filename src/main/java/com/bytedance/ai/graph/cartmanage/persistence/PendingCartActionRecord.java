package com.bytedance.ai.graph.cartmanage.persistence;

import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.cartmanage.subgraph.CartAction;
import com.bytedance.ai.graph.cartmanage.subgraph.CartWorkflowStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 购物车管理持久化记录模型。
 */
public record PendingCartActionRecord(
        Long id,
        String userId,
        String conversationId,
        CartAction action,
        String productName,
        Integer quantity,
        List<ProductCandidate> candidates,
        CartWorkflowStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime expireAt
) {
}
