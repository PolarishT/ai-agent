package com.involutionhell.backend.rag.retrieval.api;

/**
 * RAG SSE 流中的统一事件包。
 *
 * @param id 事件 ID
 * @param event SSE event name
 * @param correlationId 本次问答链路追踪 ID
 * @param data 事件负载
 */
public record RagAskStreamEvent(
        String id,
        String event,
        String correlationId,
        Object data
) {
}
