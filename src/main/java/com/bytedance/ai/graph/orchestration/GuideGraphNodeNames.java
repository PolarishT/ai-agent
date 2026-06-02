package com.bytedance.ai.graph.orchestration;

/**
 * 导购 Graph 节点名称常量。
 */
public final class GuideGraphNodeNames {

    public static final String CHECK_CONVERSATION = "check_conversation";
    public static final String LOAD_CONVERSATION_CONTEXT = "load_conversation_context";
    public static final String INIT_CONVERSATION = "init_conversation";
    public static final String SAVE_USER_MESSAGE = "save_user_message";
    public static final String MAIN_INTENT_ROUTER = "main_intent_router";
    public static final String PRODUCT_RECOMMEND_WORKFLOW = "product_recommend_workflow";
    public static final String PRODUCT_SEARCH_WORKFLOW = "product_search_workflow";
    public static final String PRODUCT_COMPARE_WORKFLOW = "product_compare_workflow";
    public static final String PRODUCT_DETAIL_QUERY_WORKFLOW = "product_detail_query_workflow";
    public static final String PRICE_QUERY_WORKFLOW = "price_query_workflow";
    public static final String INVENTORY_QUERY_WORKFLOW = "inventory_query_workflow";
    public static final String ORDER_QUERY_WORKFLOW = "order_query_workflow";
    public static final String LOGISTICS_QUERY_WORKFLOW = "logistics_query_workflow";
    public static final String ADD_TO_CART_WORKFLOW = "add_to_cart_workflow";
    public static final String REMOVE_FROM_CART_WORKFLOW = "remove_from_cart_workflow";
    public static final String UPDATE_CART_ITEM_WORKFLOW = "update_cart_item_workflow";
    public static final String CART_MANAGE_WORKFLOW = "cart_manage_workflow";
    public static final String ORDER_MANAGE_WORKFLOW = "order_manage_workflow";
    public static final String PRODUCT_QUERY_WORKFLOW = "product_query_workflow";
    public static final String CREATE_ORDER_WORKFLOW = "create_order_workflow";
    public static final String CONFIRM_ORDER_WORKFLOW = "confirm_order_workflow";
    public static final String CANCEL_ORDER_WORKFLOW = "cancel_order_workflow";
    public static final String POLICY_QA_WORKFLOW = "policy_qa_workflow";
    public static final String REVIEW_SUMMARY_WORKFLOW = "review_summary_workflow";
    public static final String CLARIFY_WORKFLOW = "clarify_workflow";
    public static final String SMALL_TALK_WORKFLOW = "small_talk_workflow";
    public static final String BUILD_ANSWER_CONTEXT = "build_answer_context";

    public static final String RECOMMEND_WORKFLOW = PRODUCT_RECOMMEND_WORKFLOW;
    public static final String SEARCH_WORKFLOW = PRODUCT_SEARCH_WORKFLOW;
    public static final String COMPARE_WORKFLOW = PRODUCT_COMPARE_WORKFLOW;

    private GuideGraphNodeNames() {
    }
}
