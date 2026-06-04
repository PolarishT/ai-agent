package com.bytedance.ai.graph.product.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * graph 侧排序后的商品候选。
 *
 * <p>融合了 product retrieval 提供的命中信息（{@code scoreKeyword} / {@code scoreSemantic}）与
 * graph 侧二次排序结果（{@code scoreFinal}），并携带 {@code matchReasons} 供
 * {@code ProductQueryResponseBuilder} 展示给用户。
 *
 * <p>{@code category} / {@code subCategory} 由 hydrate 阶段从 {@code catalog_product} 透传，
 * 供 {@link com.bytedance.ai.graph.product.query.service.ProductCandidatePostFilter} 做品类精确否定判断；
 * 可为 {@code null}。
 */
public record ProductSearchCandidate(
        Long productId,
        String externalRef,
        String title,
        String brand,
        String category,
        String subCategory,
        BigDecimal price,
        Integer stock,
        Map<String, Object> attributes,
        double scoreKeyword,
        double scoreSemantic,
        double scoreFinal,
        List<String> matchReasons,
        List<ProductReviewSnippet> reviews
) {
    public ProductSearchCandidate {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        matchReasons = matchReasons == null ? List.of() : List.copyOf(matchReasons);
        reviews = reviews == null ? List.of() : List.copyOf(reviews);
    }
}
