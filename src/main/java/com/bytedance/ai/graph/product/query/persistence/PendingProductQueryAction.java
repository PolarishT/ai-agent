package com.bytedance.ai.graph.product.query.persistence;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * pending_product_query_actions 行的内存表示。
 *
 * @param id               主键
 * @param userId           会话归属
 * @param conversationId   会话 id
 * @param condition        上一轮商品查询 condition（已 sanitize + validate）
 * @param candidates       上一轮排序后的候选商品（含 1-based 索引）
 * @param turnCount        同一会话累计的连续商品查询轮次（用于排障与对比上下文）
 * @param status           生命周期
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 * @param expireAt         过期时间；超过即被清理任务标 EXPIRED
 */
public record PendingProductQueryAction(
        Long id,
        String userId,
        String conversationId,
        ProductQueryCondition condition,
        List<ProductSearchCandidate> candidates,
        int turnCount,
        PendingProductQueryStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime expireAt
) {

    public PendingProductQueryAction {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
