package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;

/**
 * Retriever 入参对象，携带统一预算，避免各检索分支自行重复放大候选集。
 */
public record RagRetrievalRequest(
        String query,
        RagSearchFilter filter,
        RagRetrievalBudget budget
) {
}
