package com.bytedance.ai.graph.ordermanage;

import java.math.BigDecimal;
import java.util.Map;

/**
 * order_manage_workflow 终态结构化产出。由 {@code orderFinalResponse} 写入主图 state 的
 * {@code GuideGraphStateKeys.WORKFLOW_RESULT}，供下游 {@code StepOutputMapper} 投影。
 *
 * @param action          内部决策出的动作（CHECKOUT_REQUEST / CONFIRM_ORDER / CANCEL_ORDER / ...）
 * @param status          订单生命周期状态（WAITING_ADDRESS / WAITING_CONFIRMATION / ORDER_CREATED / FAILED / CANCELLED / EXPIRED）
 * @param orderNo         订单号；仅 ORDER_CREATED 时非 null
 * @param amount          订单金额快照；空时为 null
 * @param addressSnapshot 地址快照，可空
 * @param errorReason     失败 / 卡住原因，可空
 * @param needUserInput   是否还需要用户继续输入
 * @param nodeMessage     规则文案，供 LLM 兜底参考
 */
public record OrderManageWorkflowResult(
        String action,
        String status,
        String orderNo,
        BigDecimal amount,
        Map<String, Object> addressSnapshot,
        String errorReason,
        boolean needUserInput,
        String nodeMessage
) {

    public OrderManageWorkflowResult {
        addressSnapshot = addressSnapshot == null ? Map.of() : Map.copyOf(addressSnapshot);
    }
}
