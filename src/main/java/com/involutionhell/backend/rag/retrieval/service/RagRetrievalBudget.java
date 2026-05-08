package com.involutionhell.backend.rag.retrieval.service;

/**
 * 单个 retrieval query 的执行预算。
 *
 * <p>answerTopK 是整次问答最终交给 LLM 的硬上限；其余字段用于约束当前 query 在各检索分支的候选规模。</p>
 */
public record RagRetrievalBudget(
        int answerTopK,
        int queryCount,
        int perQueryTopK,
        int semanticCandidateTopK,
        int keywordCandidateTopK,
        boolean progressiveEnabled,
        String reason
) {

    public static RagRetrievalBudget legacy(int topK) {
        int normalizedTopK = Math.max(1, topK);
        return new RagRetrievalBudget(
                normalizedTopK,
                1,
                normalizedTopK,
                normalizedTopK,
                normalizedTopK,
                false,
                "legacy"
        );
    }
}
