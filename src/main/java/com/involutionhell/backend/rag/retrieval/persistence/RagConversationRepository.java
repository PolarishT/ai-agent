package com.involutionhell.backend.rag.retrieval.persistence;

import java.util.List;
import java.util.Optional;

public interface RagConversationRepository {

    Optional<RagConversationRecord> findByConversationId(String conversationId);

    Optional<RagConversationRecord> findByUserIdAndConversationId(String userId, String conversationId);

    RagConversationPage findByUserId(String userId, int limit, RagConversationCursor cursor);

    RagConversationRecord update(String userId, String conversationId, String title, String status);

    void lockById(Long id);

    void refreshStats(Long id);
}
