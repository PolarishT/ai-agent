package com.bytedance.ai.graph.api.events;

/**
 * 主图把请求路由到具体 workflow（cart_manage / order_manage / product_query / ...）
 * 后立即下发的 SSE 事件。让前端可以提前展示「正在为你查询/下单」之类的过渡状态。
 *
 * <p>本事件无论 debug 与否都发；node 级别的细粒度事件由 TRACE 模式或 debug 模式控制。
 *
 * @param workflowName 路由到的 workflow node 名（如 {@code product_query_workflow}）
 * @param intent       决定该路由的 main intent（来自 LLM / 规则）；可空表示由 initialIntent 强制覆盖
 */
public record WorkflowStartedPayload(
        String workflowName,
        String intent
) {
}
