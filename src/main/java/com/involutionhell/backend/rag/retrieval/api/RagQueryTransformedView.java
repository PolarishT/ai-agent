package com.involutionhell.backend.rag.retrieval.api;

/**
 * RAG query transform 阶段结果。
 */
public record RagQueryTransformedView(
        String originalQuestion,
        String retrievalQuestion,
        boolean queryTransformed,
        boolean transformedByModel,
        int conversationTurns
) {
}
