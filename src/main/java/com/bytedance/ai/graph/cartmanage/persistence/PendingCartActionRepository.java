package com.bytedance.ai.graph.cartmanage.persistence;

import java.util.Optional;

/**
 * 购物车管理仓储端口，隔离领域逻辑与底层持久化实现。
 */
public interface PendingCartActionRepository {

    PendingCartActionRecord save(PendingCartActionRecord record);

    Optional<PendingCartActionRecord> findActiveByUserIdAndConversationId(String userId, String conversationId);

    void markCompleted(Long id);

    void markCancelled(Long id);

    void deleteExpired();
}
