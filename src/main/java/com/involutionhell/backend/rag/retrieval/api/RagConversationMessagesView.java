package com.involutionhell.backend.rag.retrieval.api;

import java.util.List;

public record RagConversationMessagesView(
        String conversationId,
        String userId,
        List<RagConversationMessageView> messages
) {
}
