package com.involutionhell.backend.rag.retrieval.persistence;

import java.util.List;

public record RagConversationPage(
        List<RagConversationRecord> items,
        RagConversationCursor nextCursor
) {
}
