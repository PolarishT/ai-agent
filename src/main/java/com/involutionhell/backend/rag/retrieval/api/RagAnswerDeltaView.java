package com.involutionhell.backend.rag.retrieval.api;

/**
 * RAG 回答增量文本事件。
 */
public record RagAnswerDeltaView(
        String delta
) {
}
