package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import java.util.List;

/**
 * 检索层抽象。
 */
public interface RagRetriever {

    List<RagRetrievedChunk> search(RagRetrievalRequest request);

    @Deprecated
    default List<RagRetrievedChunk> search(String question, int topK, RagSearchFilter filter) {
        return search(new RagRetrievalRequest(question, filter, RagRetrievalBudget.legacy(topK)));
    }
}
