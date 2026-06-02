package com.bytedance.ai.graph.conversation.persistence;

import com.bytedance.ai.graph.conversation.ConversationMessage;
import java.util.List;

/**
 * 会话仓储端口，隔离领域逻辑与底层持久化实现。
 */
public interface AgentConversationRepository {

    boolean existsConversation(String userId, String conversationId);

    Long initConversation(String userId, String conversationId);

    /**
     * 原子分配会话内单调递增的 turn_id。
     * 格式：turn_&lt;base36(内部会话 id)&gt;_&lt;6 位 0 填充序号&gt;。
     */
    String allocateTurnId(String userId, String conversationId);

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
