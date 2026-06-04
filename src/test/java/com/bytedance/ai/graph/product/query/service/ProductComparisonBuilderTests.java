package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.ProductComparisonResult;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import com.bytedance.ai.shared.properties.RagProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductComparisonBuilderTests {

    private final ProductComparisonBuilder builder = new ProductComparisonBuilder(RagProperties.defaults());

    @Test
    void usesTopTwoWhenTargetsEmpty() {
        ProductComparisonResult result = builder.build(List.of(
                candidate(1L, "A", "Sony", "299", 10),
                candidate(2L, "B", "Bose", "599", 5),
                candidate(3L, "C", "Apple", "999", 2)
        ), List.of());

        assertThat(result.rows()).extracting(ProductComparisonResult.Row::index)
                .containsExactly(1, 2);
    }

    @Test
    void usesRequestedTargetsAndClampsOutOfRange() {
        ProductComparisonResult result = builder.build(List.of(
                candidate(1L, "A", "Sony", "299", 10),
                candidate(2L, "B", "Bose", "599", 5)
        ), List.of(2, 99));

        assertThat(result.rows()).extracting(ProductComparisonResult.Row::index)
                .containsExactly(2);
    }

    @Test
    void emptyCandidatesReturnEmptyComparison() {
        ProductComparisonResult result = builder.build(List.of(), List.of());
        assertThat(result.rows()).isEmpty();
        assertThat(result.summary()).contains("暂无可对比");
    }

    @Test
    void summaryHighlightsCheapest() {
        ProductComparisonResult result = builder.build(List.of(
                candidate(1L, "A", "Sony", "599", 10),
                candidate(2L, "B", "Bose", "299", 5)
        ), List.of(1, 2));

        assertThat(result.summary()).contains("第 2 件", "¥299");
    }

    private ProductSearchCandidate candidate(Long productId, String title, String brand, String price, int stock) {
        return new ProductSearchCandidate(
                productId, "ref-" + productId, title, brand,
                null, null,
                new BigDecimal(price), stock, Map.of(),
                0.5d, 0.5d, 0.8d,
                List.of("price_match"),
                List.of()
        );
    }
}
