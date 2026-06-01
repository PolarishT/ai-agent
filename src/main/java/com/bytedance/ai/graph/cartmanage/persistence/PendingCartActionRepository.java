package com.bytedance.ai.graph.cartmanage.persistence;

import java.util.Optional;

public interface PendingCartActionRepository {

    PendingCartActionRecord save(PendingCartActionRecord record);

    Optional<PendingCartActionRecord> findActiveByUserIdAndConversationId(String userId, String conversationId);

    void markCompleted(Long id);

    void markCancelled(Long id);

    void deleteExpired();
}
