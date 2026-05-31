package com.bytedance.ai.graph.product.retrieval;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductQueryIntent;

/**
 * 商品检索请求。
 *
 * <p>caller 只表达「想搜什么、最多要几个、加什么硬过滤、走哪个 intent」。
 *
 * @param query              关键词分支 query（必填）
 * @param topK               期望返回的 Product 数；为 null / ≤0 时按默认 topK
 * @param hardFilter         商品业务硬过滤（priceMax/brand/category/excludeColor 等）；
 *                            keyword / semantic 分支都会在 PostgreSQL 侧应用。可为 null。
 * @param semanticQuery      语义检索分支专用 query；为空 / null 时回落到 {@code query}。
 *                            场景：用户原始描述较口语化，{@code keywordQuery} 走结构化关键词、
 *                            {@code semanticQuery} 走完整自然语言。
 * @param condition          原始解析得到的 {@link ProductQueryCondition}。
 *                            keyword 分支使用 {@code ProductPostgresFilterBuilder} 生成 SQL，
 *                            semantic 分支使用 {@code MilvusScalarFilterBuilder} 生成 expr。
 *                            为 null 时 retriever 仅依赖 {@link #hardFilter} 兼容旧调用方。
 * @param intent             子图根据 MainIntent + condition.intent 决定的子意图，决定 keyword
 *                            分支落到哪个原始业务表（catalog / sku / review / faq / knowledge）。
 *                            为 null 时按 {@link ProductQueryIntent#PRODUCT_SEARCH} 处理。
 */
public record ProductSearchRequest(
        String query,
        Integer topK,
        ProductHardFilter hardFilter,
        String semanticQuery,
        ProductQueryCondition condition,
        ProductQueryIntent intent
) {
    public ProductSearchRequest(String query, Integer topK, ProductHardFilter hardFilter, String semanticQuery) {
        this(query, topK, hardFilter, semanticQuery, null, null);
    }

    public ProductSearchRequest(
            String query,
            Integer topK,
            ProductHardFilter hardFilter,
            String semanticQuery,
            ProductQueryCondition condition
    ) {
        this(query, topK, hardFilter, semanticQuery, condition, null);
    }

    public ProductQueryIntent intentOrDefault() {
        return intent == null ? ProductQueryIntent.PRODUCT_SEARCH : intent;
    }
}
