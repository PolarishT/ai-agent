package com.bytedance.ai.graph.product.query.persistence;

import java.util.Optional;

/**
 * pending_product_query_actions 仓储接口。
 *
 * <p>同模块（{@code graph}）的其它子图（cartmanage）后续也可以读取本表以解析
 * "把第二个加入购物车" 这类跨 workflow 引用。
 */
public interface PendingProductQueryRepository {

    /**
     * 新增一行（INSERT），把上一轮的 ACTIVE 行标为 SUPERSEDED 以保证同会话仅一条 ACTIVE。
     */
    PendingProductQueryAction save(PendingProductQueryAction record);

    /**
     * 取当前会话最新的 ACTIVE 行（未过期）。
     */
    Optional<PendingProductQueryAction> findActiveByUserIdAndConversationId(String userId, String conversationId);

    void markSuperseded(Long id);

    void markExpired(Long id);

    int deleteExpired();
}
