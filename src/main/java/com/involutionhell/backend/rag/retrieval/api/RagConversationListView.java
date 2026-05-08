package com.involutionhell.backend.rag.retrieval.api;

import java.util.List;

public record RagConversationListView(
        List<RagConversationSummaryView> items,
        String nextCursor
) {
}
