package com.bytedance.ai.agent.application;

import com.bytedance.ai.retrieval.spi.AgentConversationSpi;
import com.bytedance.ai.retrieval.spi.AgentTurnConversationState;
import org.springframework.stereotype.Component;

@Component
public class ConversationTurnAdapter {

    private final AgentConversationSpi conversationSpi;

    public ConversationTurnAdapter(AgentConversationSpi conversationSpi) {
        this.conversationSpi = conversationSpi;
    }

    public AgentTurnConversationState begin(
            String userId,
            String conversationId,
            String userMessage,
            String correlationId
    ) {
        return conversationSpi.beginTurn(userId, conversationId, userMessage, correlationId);
    }

    public void complete(String assistantMessageId, String answerText) {
        conversationSpi.completeTurn(assistantMessageId, answerText);
    }

    public void fail(String assistantMessageId, String errorCode, String errorMessage) {
        conversationSpi.failTurn(assistantMessageId, errorCode, errorMessage);
    }
}
