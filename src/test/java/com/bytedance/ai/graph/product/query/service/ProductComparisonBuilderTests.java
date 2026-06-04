package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.ProductComparisonResult;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import com.bytedance.ai.shared.properties.RagProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductComparisonBuilderTests {

    private final ProductComparisonBuilder builder = new ProductComparisonBuilder(
            RagProperties.defaults(),
            new ProductComparisonDimensionSelector()
    );

    @Test
    void usesTopTwoWhenTargetsEmpty() {
        ProductComparisonResult result = builder.build(List.of(
                candidate(1L, "A", "Sony", "299", 10),
                candidate(2L, "B", "Bose", "599", 5),
                candidate(3L, "C", "Apple", "999", 2)
        ), List.of());

        assertThat(result.rows()).extracting(ProductComparisonResult.Row::index)
                .containsExactly(1, 2);
        assertThat(result.products()).extracting(ProductComparisonResult.ProductColumn::index)
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

    @Test
    void usesTextTargetsWhenProvided() {
        ProductQueryCondition condition = new ProductQueryCondition(
                "对比 Bose 和 Apple", "对比 Bose 和 Apple", "COMPARE", "HYBRID",
                "Bose Apple", "Bose Apple",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                null,
                null, null, null,
                "RELEVANCE", "RESET", List.of(),
                List.of("Bose", "Apple"),
                List.of("性价比"),
                List.of("价格"),
                true, 0.9d, false, List.of()
        );

        ProductComparisonResult result = builder.build(List.of(
                candidate(1L, "A", "Sony", "299", 10),
                candidate(2L, "B", "Bose", "599", 5),
                candidate(3L, "C", "Apple", "999", 2)
        ), List.of(), condition);

        assertThat(result.rows()).extracting(ProductComparisonResult.Row::index)
                .containsExactly(2, 3);
        assertThat(result.dimensionRows()).extracting(ProductComparisonResult.DimensionRow::key)
                .contains("price");
    }

    @Test
    void dynamicDimensionsIncludeRequestedAndFocusAttributes() {
        ProductQueryCondition condition = new ProductQueryCondition(
                "哪款更适合通勤，按续航对比", "通勤 续航", "COMPARE", "HYBRID",
                "通勤 续航", "通勤 续航",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                null,
                null, null, null,
                "RELEVANCE", "RESET", List.of(),
                List.of(),
                List.of("通勤"),
                List.of("续航"),
                true, 0.9d, false, List.of()
        );

        ProductComparisonResult result = builder.build(List.of(
                candidate(1L, "A", "Sony", "299", 10,
                        Map.of("battery", "8h", "weight", "180g")),
                candidate(2L, "B", "Bose", "599", 5,
                        Map.of("battery", "12h", "weight", "220g"))
        ), List.of(), condition);

        assertThat(result.dimensionRows()).extracting(ProductComparisonResult.DimensionRow::key)
                .contains("battery", "weight");
        assertThat(result.decision().recommendedIndex()).isNotNull();
    }

    private ProductSearchCandidate candidate(Long productId, String title, String brand, String price, int stock) {
        return candidate(productId, title, brand, price, stock, Map.of());
    }

    private ProductSearchCandidate candidate(
            Long productId,
            String title,
            String brand,
            String price,
            int stock,
            Map<String, Object> attributes
    ) {
        return new ProductSearchCandidate(
                productId, "ref-" + productId, title, brand,
                null, null,
                new BigDecimal(price), stock, attributes,
                0.5d, 0.5d, 0.8d,
                List.of("price_match"),
                List.of()
        );
    }
}
