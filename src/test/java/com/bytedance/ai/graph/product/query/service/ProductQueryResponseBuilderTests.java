package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.AttributeIncludeExclude;
import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductReviewSnippet;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductQueryResponseBuilderTests {

    private final ProductQueryResponseBuilder builder = new ProductQueryResponseBuilder();

    @Test
    void listResponseSummarizesConditionAndCandidates() {
        ProductQueryCondition condition = condition(new BigDecimal("300"), List.of("黑色"));
        String response = builder.buildListResponse(
                condition,
                List.of(
                        candidate(1L, "蓝牙耳机 Pro", "Sony", "289"),
                        candidate(2L, "蓝牙耳机 Lite", "Bose", "199")
                ),
                List.of()
        );

        assertThat(response).contains("价格 ≤ ¥300");
        assertThat(response).contains("已为你排除");
        assertThat(response).contains("蓝牙耳机 Pro");
    }

    @Test
    void zeroHitsDiagnosesTightestConstraint() {
        ProductQueryCondition condition = condition(new BigDecimal("100"), List.of("黑色"));
        String response = builder.buildListResponse(condition, List.of(), List.of());
        assertThat(response).contains("暂时没有");
        assertThat(response).contains("价格 ≤ 100");
    }

    @Test
    void degradedNotesAppearedInTail() {
        ProductQueryCondition condition = condition(null, List.of());
        String response = builder.buildListResponse(
                condition,
                List.of(candidate(1L, "蓝牙耳机", "Sony", "199")),
                List.of("semantic_timeout")
        );
        assertThat(response).contains("semantic_timeout");
    }

    @Test
    void listResponseIncludesHydratedReviews() {
        ProductQueryCondition condition = condition(null, List.of());
        ProductSearchCandidate candidate = new ProductSearchCandidate(
                1L, "ref-1", "三顿半数字星球咖啡", "三顿半",
                "咖啡", null,
                new BigDecimal("129"), 10, Map.of(),
                0.5d, 0.5d, 0.8d, List.of(),
                List.of(new ProductReviewSnippet(0, "小林", 5, "POSITIVE", "冲泡很方便，风味比普通速溶自然"))
        );

        String response = builder.buildListResponse(condition, List.of(candidate), List.of());

        assertThat(response).contains("评论1", "5星", "小林说", "冲泡很方便");
    }

    @Test
    void clarifyResponseListsMissingSlots() {
        ProductQueryCondition condition = new ProductQueryCondition(
                "再便宜点", "再便宜点", "REFINE", "HYBRID", "再便宜点", "再便宜点",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                ProductAttributesCondition.empty(),
                null, null, null,
                "RELEVANCE", "INHERIT", List.of(),
                false, 0.2d, true, List.of("category")
        );
        String response = builder.buildClarifyResponse(condition);
        assertThat(response).contains("category");
    }

    private ProductQueryCondition condition(BigDecimal priceMax, List<String> excludeColors) {
        ProductAttributesCondition attributes = new ProductAttributesCondition(
                new AttributeIncludeExclude(List.of(), excludeColors),
                AttributeIncludeExclude.empty(),
                AttributeIncludeExclude.empty(),
                null
        );
        return new ProductQueryCondition(
                "raw", "蓝牙耳机", "QUERY", "HYBRID", "蓝牙耳机", "蓝牙耳机",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                attributes,
                null, priceMax, null,
                "RELEVANCE", "RESET", List.of(),
                false, 0.9d, false, List.of()
        );
    }

    private ProductSearchCandidate candidate(Long productId, String title, String brand, String price) {
        return new ProductSearchCandidate(
                productId, "ref-" + productId, title, brand,
                null, null,
                new BigDecimal(price), 10, Map.of(),
                0.5d, 0.5d, 0.8d, List.of("price_match≤300"), List.of()
        );
    }
}
