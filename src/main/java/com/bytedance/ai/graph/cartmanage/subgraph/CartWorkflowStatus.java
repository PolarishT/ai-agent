package com.bytedance.ai.graph.cartmanage.subgraph;

/**
 * 购物车管理流程状态枚举。
 */
public enum CartWorkflowStatus {
    ADD_SUCCESS,
    REMOVE_SUCCESS,
    UPDATE_SUCCESS,
    VIEW_SUCCESS,
    CLEAR_SUCCESS,
    WAITING_CLARIFICATION,
    WAITING_USER_SELECTION,
    PRODUCT_NOT_FOUND,
    PRODUCT_CONSTRAINT_NOT_MATCHED,
    STOCK_NOT_ENOUGH,
    INVALID_QUANTITY,
    CART_EMPTY,
    ITEM_NOT_FOUND,
    CANCELLED,
    FAILED
}
