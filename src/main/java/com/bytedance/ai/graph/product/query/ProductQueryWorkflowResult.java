package com.bytedance.ai.graph.product.query;

import java.util.List;

/**
 * product_query_workflow 终态结构化产出。由 {@code pqFinalResponse} 写入主图 state 的
 * {@code GuideGraphStateKeys.WORKFLOW_RESULT}，下游 {@code StepOutputMapper}
 * 据此投影成 LLM 看得懂的 step output。
 *
 * @param status        OK / NO_HITS / CLARIFY
 * @param candidates    排序 + 后过滤后的最终候选，按 score_final 从高到低
 * @param comparison    比较意图下的对比结果，否则 null
 * @param degradedNotes 检索 / 后处理过程中的降级原因，可空
 * @param nodeMessage   规则文案，供 LLM 兜底参考
 */
public record ProductQueryWorkflowResult(
        String status,
        List<ProductSearchCandidate> candidates,
        ProductComparisonResult comparison,
        List<String> degradedNotes,
        String nodeMessage
) {

    public ProductQueryWorkflowResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        degradedNotes = degradedNotes == null ? List.of() : List.copyOf(degradedNotes);
    }
}
