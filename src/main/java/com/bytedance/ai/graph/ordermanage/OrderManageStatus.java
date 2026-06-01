package com.bytedance.ai.graph.ordermanage;

/**
 * 订单管理流程状态枚举。
 */
public enum OrderManageStatus {
    WAITING_ADDRESS,
    WAITING_CONFIRMATION,
    CREATING,
    ORDER_CREATED,
    CANCELLED,
    FAILED,
    EXPIRED
}
