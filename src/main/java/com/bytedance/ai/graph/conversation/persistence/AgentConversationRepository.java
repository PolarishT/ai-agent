package com.bytedance.ai.graph.conversation.persistence;

import com.bytedance.ai.graph.conversation.ConversationMessage;
import java.util.List;

public interface AgentConversationRepository {

    boolean existsConversation(String userId, String conversationId);

    Long initConversation(String userId, String conversationId);

    List<ConversationMessage> loadRecentMessages(String userId, String conversationId, int limit);

    ConversationMessage saveUserMessage(
            String userId,
            String conversationId,
            String turnId,
            String correlationId,
            String content
    );

    ConversationMessage saveAssistantMessage(
            String userId,
            String conversationId,
            String turnId,
            String correlationId,
            String content,
            String status
    );

    void createOrUpdateTurn(
            String userId,
            String conversationId,
            String turnId,
            String requestId,
            String status,
            String intent,
            String targetWorkflow
    );
}
