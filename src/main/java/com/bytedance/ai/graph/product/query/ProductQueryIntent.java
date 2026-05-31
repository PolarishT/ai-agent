package com.bytedance.ai.graph.product.query;

import com.bytedance.ai.graph.intent.MainIntent;

/**
 * product_query_workflow 内部用于决定 hydrate 范围 / response 形态的子意图。
 *
 * <p>区别于 {@link MainIntent}：MainIntent 是上游路由用的全局意图集合（覆盖购物车、订单、闲聊 etc.），
 * 本枚举只关注子图内部「需要什么 hydrate 字段、要不要返回 review/FAQ」的小尺度差异。
 *
 * <p>映射规则见 {@link #from(MainIntent, String)}：默认走 {@link #PRODUCT_SEARCH}。
 */
public enum ProductQueryIntent {

    /** 详细问答类，需要完整 SKU / chunk / FAQ / knowledge，不需要 review。 */
    PRODUCT_QA,
    /** 推荐场景，需要 SKU / 简要 chunk / 卖点 / 少量 review 摘要。 */
    PRODUCT_RECOMMEND,
    /** 搜索列表，基础信息 + SKU + 价格/库存。 */
    PRODUCT_SEARCH,
    /** 多商品对比。 */
    PRODUCT_COMPARE,
    /** 围绕 review 的问答。 */
    REVIEW_QA,
    /** FAQ 问答（保质期 / 适用人群 / 保存方式等明确问答型）。 */
    FAQ_QA,
    /** 库存查询。 */
    INVENTORY_CHECK,
    /** 价格查询。 */
    PRICE_QA;

    public static ProductQueryIntent from(MainIntent mainIntent, String conditionIntent) {
        if (mainIntent != null) {
            switch (mainIntent) {
                case PRODUCT_RECOMMEND -> {
                    return PRODUCT_RECOMMEND;
                }
                case PRODUCT_SEARCH, PRODUCT_QUERY -> {
                    return PRODUCT_SEARCH;
                }
                case PRODUCT_COMPARE -> {
                    return PRODUCT_COMPARE;
                }
                case PRODUCT_DETAIL_QUERY -> {
                    return PRODUCT_QA;
                }
                case REVIEW_SUMMARY -> {
                    return REVIEW_QA;
                }
                case INVENTORY_QUERY -> {
                    return INVENTORY_CHECK;
                }
                case PRICE_QUERY -> {
                    return PRICE_QA;
                }
                default -> { /* fall through to conditionIntent */ }
            }
        }
        if (conditionIntent != null) {
            return switch (conditionIntent.toUpperCase()) {
                case "COMPARE" -> PRODUCT_COMPARE;
                case "REVIEW_QA" -> REVIEW_QA;
                case "FAQ_QA", "FAQ" -> FAQ_QA;
                case "PRICE_QA" -> PRICE_QA;
                case "INVENTORY_CHECK" -> INVENTORY_CHECK;
                case "RECOMMEND" -> PRODUCT_RECOMMEND;
                case "QA", "DETAIL" -> PRODUCT_QA;
                default -> PRODUCT_SEARCH;
            };
        }
        return PRODUCT_SEARCH;
    }
}
