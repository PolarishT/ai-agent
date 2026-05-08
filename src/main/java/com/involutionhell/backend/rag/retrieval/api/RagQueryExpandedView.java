package com.involutionhell.backend.rag.retrieval.api;

import java.util.List;

/**
 * RAG query expansion 阶段结果。
 */
public record RagQueryExpandedView(
        String originalQuestion,
        List<String> retrievalQueries,
        boolean queryExpanded,
        boolean expandedByModel
) {
}
