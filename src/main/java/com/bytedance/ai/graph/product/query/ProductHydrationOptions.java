package com.bytedance.ai.graph.product.query;

/**
 * hydrate 阶段需要带哪些字段的开关集合，由 {@link ProductQueryIntent} 决定。
 *
 * <p>具体的 hydrator（在 product retrieval / catalog 侧实现）按本配置决定是否拉取
 * SKU 列表、商品描述（attributes_json + raw_json）、chunk 命中片段、FAQ、knowledge、reviews，
 * 以及 chunk / review 各自取多少条。
 *
 * @param includeSku         是否拉取 catalog_sku 列表
 * @param includeDescription 是否拉取商品描述（attributes_json + raw_json）
 * @param includeChunks      是否带回命中的 rag_chunks 片段
 * @param includeFaq         是否带回 catalog_product_faq
 * @param includeKnowledge   是否带回 catalog_product_knowledge
 * @param includeReviews     是否带回 catalog_product_review
 * @param chunkLimit         单个 product 最多取多少条 chunk（仅当 includeChunks=true 生效）
 * @param reviewLimit        单个 product 最多取多少条 review（仅当 includeReviews=true 生效）
 */
public record ProductHydrationOptions(
        boolean includeSku,
        boolean includeDescription,
        boolean includeChunks,
        boolean includeFaq,
        boolean includeKnowledge,
        boolean includeReviews,
        int chunkLimit,
        int reviewLimit
) {
    /**
     * 最小集合：仅 catalog_product 主表字段。
     */
    public static ProductHydrationOptions basic() {
        return new ProductHydrationOptions(false, false, false, false, false, false, 0, 0);
    }
}
