package com.bytedance.ai.graph.product.query;

/**
 * product_query_workflow 子图独占的 state key。
 *
 * <p>命名空间和 cartmanage / ordermanage 保持隔离（前缀 {@code product_query_*}），
 * 主图 {@code build_answer_context} 通过 {@link #NODE_MESSAGE} 读回写文字答案。
 */
public final class ProductQueryGraphStateKeys {

    public static final String PRODUCT_QUERY_CONDITION = "product_query_condition";
    public static final String PRODUCT_QUERY_INTENT = "product_query_intent";
    public static final String PRODUCT_HYDRATION_OPTIONS = "product_query_hydration_options";
    public static final String PRODUCT_SEARCH_FILTER = "product_search_filter";
    public static final String PRODUCT_SEARCH_RESULT = "product_search_result";
    public static final String KEYWORD_HITS = "product_query_keyword_hits";
    public static final String SEMANTIC_HITS = "product_query_semantic_hits";
    public static final String FUSED_HITS = "product_query_fused_hits";
    public static final String RANKED_PRODUCTS = "product_query_ranked_products";
    public static final String POST_FILTERED_PRODUCTS = "product_query_post_filtered_products";
    public static final String POST_FILTER_REASONS = "product_query_post_filter_reasons";
    public static final String COMPARISON_RESULT = "product_query_comparison_result";
    public static final String NEED_USER_INPUT = "product_query_need_user_input";
    public static final String NODE_MESSAGE = "product_query_node_message";
    public static final String LAST_PRODUCT_QUERY_CONTEXT = "last_product_query_context";
    public static final String PENDING_PRODUCT_QUERY_ID = "pending_product_query_id";
    public static final String WORKFLOW_STATUS = "product_query_workflow_status";
    public static final String DEGRADED_NOTES = "product_query_degraded_notes";

    private ProductQueryGraphStateKeys() {
    }
}
