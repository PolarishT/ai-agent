package com.involutionhell.backend.rag.retrieval.api;

import java.util.List;

/**
 * RAG 问答流完成事件。
 */
public record RagAskCompletedView(
        boolean generatedByModel,
        boolean degraded,
        List<RagResponseNoticeView> notices,
        int contextCount
) {
}
