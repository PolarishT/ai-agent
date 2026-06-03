package com.bytedance.ai.graph.conversation.context;

import java.util.Map;

/**
 * 把 workflow 执行结果投影成 step 的 {@code output} map（写进 taskSummaries.steps[].output）。
 *
 * <p>新架构下 step 的分类由 {@code taskType} 承载，不再需要 {@code kind} 判别字段，
 * 因此 output 直接是离散字段 map（例如 PRODUCT_SEARCH → {@code {candidateCount, productInfo[]}}）。
 *
 * <p>接口放在 {@code conversation.context} 包，实现放在 {@code answer} 包以避免与各 domain workflow 形成循环依赖。
 */
public interface StepOutputMapper {

    /**
     * @param taskType       当前任务类型（PRODUCT_SEARCH / ADD_TO_CART / CREATE_ORDER / ...）
     * @param workflowResult workflow 节点产出对象（可能为 null）
     * @return 结构化 output map；至少为空 map，不为 null
     */
    Map<String, Object> toOutput(String taskType, Object workflowResult);
}
