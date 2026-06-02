package com.bytedance.ai.graph.cartmanage.subgraph;

/**
 * 购物车管理Graph 状态 key 常量集合。
 */
public final class CartGraphStateKeys {

    public static final String CART_ACTION = "cart_action";
    public static final String CART_STATUS = "cart_status";
    public static final String PRODUCT_ID = "product_id";
    public static final String SKU_ID = "sku_id";
    public static final String PRODUCT_NAME = "product_name";
    public static final String EXPECTED_PRICE = "expected_price";
    public static final String QUANTITY = "quantity";
    public static final String ITEM_INDEX = "item_index";
    public static final String CONTEXTUAL_REFERENCE = "contextual_reference";
    public static final String PRODUCT_CANDIDATES = "product_candidates";
    public static final String SELECTED_CANDIDATE = "selected_candidate";
    public static final String PENDING_CLARIFICATION_ID = "pending_clarification_id";
    public static final String STOCK_RESULT = "stock_result";
    public static final String CART_RESULT = "cart_result";
    public static final String WORKFLOW_STATUS = "workflow_status";
    public static final String CLARIFY_REASON = "clarify_reason";
    public static final String NEED_USER_INPUT = "need_user_input";
    public static final String NODE_MESSAGE = "node_message";

    private CartGraphStateKeys() {
    }
}
