package com.bytedance.ai.graph.ordermanage;

/**
 * 订单管理Graph 状态 key 常量集合。
 */
public final class OrderManageStateKeys {

    public static final String ORDER_ACTION = "orderAction";
    public static final String ORDER_CONTEXT_ITEM_ID = "orderContextItemId";
    public static final String ORDER_STATUS = "orderStatus";
    public static final String CART_SNAPSHOT = "cartSnapshot";
    public static final String CART_SNAPSHOT_HASH = "cartSnapshotHash";
    public static final String ADDRESS_SNAPSHOT = "addressSnapshot";
    public static final String AMOUNT_SNAPSHOT = "amountSnapshot";
    public static final String ORDER_NO = "orderNo";
    public static final String NODE_MESSAGE = "nodeMessage";
    public static final String NEED_USER_INPUT = "needUserInput";
    public static final String ERROR_REASON = "orderErrorReason";

    private OrderManageStateKeys() {
    }
}
