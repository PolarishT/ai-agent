package com.bytedance.ai.graph.orchestration;

import com.bytedance.ai.graph.intent.MainIntent;
import com.bytedance.ai.graph.intent.MainIntentWorkflowMapping;
import com.bytedance.ai.graph.api.GuideGraphIntent;

import java.util.EnumMap;
import java.util.Map;

/**
 * 导购工作流名称常量。
 */
public final class GuideGraphWorkflows {

    private static final Map<GuideGraphIntent, String> TARGETS = new EnumMap<>(GuideGraphIntent.class);

    static {
        // 所有 product 系列 intent 收敛到统一的 product_query_workflow subgraph.
        TARGETS.put(GuideGraphIntent.PRODUCT_QUERY, GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW);
        TARGETS.put(GuideGraphIntent.PRODUCT_RECOMMEND, GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW);
        TARGETS.put(GuideGraphIntent.PRODUCT_SEARCH, GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW);
        TARGETS.put(GuideGraphIntent.PRODUCT_COMPARE, GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW);
        TARGETS.put(GuideGraphIntent.PRODUCT_DETAIL_QUERY, GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW);
        TARGETS.put(GuideGraphIntent.PRICE_QUERY, GuideGraphNodeNames.PRICE_QUERY_WORKFLOW);
        TARGETS.put(GuideGraphIntent.INVENTORY_QUERY, GuideGraphNodeNames.INVENTORY_QUERY_WORKFLOW);
        TARGETS.put(GuideGraphIntent.ORDER_QUERY, GuideGraphNodeNames.ORDER_QUERY_WORKFLOW);
        TARGETS.put(GuideGraphIntent.LOGISTICS_QUERY, GuideGraphNodeNames.LOGISTICS_QUERY_WORKFLOW);
        // All cart-management intents (legacy granular + new unified) route to the single
        // cart_manage_workflow node. The node does its own slot filling (CartManageSlotFillingService)
        // and dispatches internally to view/remove/update-qty/clear branches.
        TARGETS.put(GuideGraphIntent.CART_MANAGE, GuideGraphNodeNames.CART_MANAGE_WORKFLOW);
        TARGETS.put(GuideGraphIntent.ADD_TO_CART, GuideGraphNodeNames.CART_MANAGE_WORKFLOW);
        TARGETS.put(GuideGraphIntent.REMOVE_FROM_CART, GuideGraphNodeNames.CART_MANAGE_WORKFLOW);
        TARGETS.put(GuideGraphIntent.UPDATE_CART_ITEM, GuideGraphNodeNames.CART_MANAGE_WORKFLOW);
        TARGETS.put(GuideGraphIntent.CREATE_ORDER, GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW);
        TARGETS.put(GuideGraphIntent.CONFIRM_ORDER, GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW);
        TARGETS.put(GuideGraphIntent.CANCEL_ORDER, GuideGraphNodeNames.ORDER_MANAGE_WORKFLOW);
        TARGETS.put(GuideGraphIntent.POLICY_QA, GuideGraphNodeNames.POLICY_QA_WORKFLOW);
        TARGETS.put(GuideGraphIntent.REVIEW_SUMMARY, GuideGraphNodeNames.PRODUCT_QUERY_WORKFLOW);
        TARGETS.put(GuideGraphIntent.CLARIFY, GuideGraphNodeNames.CLARIFY_WORKFLOW);
        TARGETS.put(GuideGraphIntent.SMALL_TALK, GuideGraphNodeNames.SMALL_TALK_WORKFLOW);
        TARGETS.put(GuideGraphIntent.UNKNOWN, GuideGraphNodeNames.CLARIFY_WORKFLOW);
    }

    private GuideGraphWorkflows() {
    }

    public static String targetFor(GuideGraphIntent intent) {
        return TARGETS.getOrDefault(intent, GuideGraphNodeNames.CLARIFY_WORKFLOW);
    }

    public static String targetFor(MainIntent intent) {
        return MainIntentWorkflowMapping.targetWorkflowOf(intent);
    }

    public static Map<GuideGraphIntent, String> targets() {
        return Map.copyOf(TARGETS);
    }
}
