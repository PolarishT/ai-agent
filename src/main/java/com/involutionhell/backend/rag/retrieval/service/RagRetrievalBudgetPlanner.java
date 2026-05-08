package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import java.util.List;

public interface RagRetrievalBudgetPlanner {

    RagRetrievalBudget plan(
            String originalQuestion,
            List<String> retrievalQueries,
            RagSearchFilter filter,
            int requestedTopK,
            boolean isRetry
    );
}
