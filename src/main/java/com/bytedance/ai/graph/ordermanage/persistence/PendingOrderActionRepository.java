package com.bytedance.ai.graph.ordermanage.persistence;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public interface PendingOrderActionRepository {

    Optional<PendingOrderActionRecord> findActiveByUserIdAndConversationId(String userId, String conversationId);

    Optional<PendingOrderActionRecord> findById(Long id);

    PendingOrderActionRecord save(PendingOrderActionRecord record);

    void updateAddress(Long id, Map<String, Object> addressSnapshot);

    void markWaitingAddress(Long id);

    void markWaitingConfirmation(
            Long id,
            Map<String, Object> cartSnapshot,
            String cartSnapshotHash,
            Map<String, Object> addressSnapshot,
            BigDecimal amount
    );

    boolean markCreatingIfWaitingConfirmation(Long id);

    void markCreated(Long id, String orderNo);

    void markCancelled(Long id);

    void markFailed(Long id, String reason);

    void markExpired(Long id);
}
