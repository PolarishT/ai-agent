package com.bytedance.ai.graph.cart.workflow;

/**
 * 购物车事件枚举。
 */
public enum CartEvent {
    PROPOSE_ITEM,
    CONFIRM_ADD,
    REMOVE,
    UPDATE_QTY,
    CHECKOUT,
    CANCEL
}
