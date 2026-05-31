package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.AttributeIncludeExclude;
import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductQueryConditionMergerTests {

    private final ProductQueryConditionMerger merger = new ProductQueryConditionMerger();

    @Test
    void inheritTightensPriceWhenCurrentHasNone() {
        ProductQueryCondition previous = baseCondition().priceMax(new BigDecimal("300")).build();
        ProductQueryCondition current = baseCondition()
                .refineType("INHERIT")
                .priceMax(null)
                .build();

        ProductQueryCondition merged = merger.merge(current, previous);

        // 0.8 * 300 = 240
        assertThat(merged.priceMax()).isEqualByComparingTo("240.00");
    }

    @Test
    void overrideRemovesPreviousExcludeForSameAttribute() {
        ProductQueryCondition previous = baseCondition()
                .attributes(new ProductAttributesCondition(
                        new AttributeIncludeExclude(List.of(), List.of("黑色")),
                        AttributeIncludeExclude.empty(),
                        AttributeIncludeExclude.empty(),
                        null
                ))
                .build();
        ProductQueryCondition current = baseCondition()
                .refineType("OVERRIDE")
                .attributes(new ProductAttributesCondition(
                        new AttributeIncludeExclude(List.of("黑色"), List.of()),
                        AttributeIncludeExclude.empty(),
                        AttributeIncludeExclude.empty(),
                        null
                ))
                .build();

        ProductQueryCondition merged = merger.merge(current, previous);

        assertThat(merged.attributes().color().include()).containsExactly("黑色");
        assertThat(merged.attributes().color().exclude()).isEmpty();
    }

    @Test
    void appendAddsToPreviousExclude() {
        ProductQueryCondition previous = baseCondition()
                .excludeTerms(List.of("黑色"))
                .build();
        ProductQueryCondition current = baseCondition()
                .refineType("APPEND")
                .excludeTerms(List.of("塑料"))
                .build();

        ProductQueryCondition merged = merger.merge(current, previous);

        assertThat(merged.excludeTerms()).containsExactlyInAnyOrder("黑色", "塑料");
    }

    @Test
    void resetDiscardsPreviousFields() {
        ProductQueryCondition previous = baseCondition()
                .priceMax(new BigDecimal("300"))
                .excludeTerms(List.of("黑色"))
                .build();
        ProductQueryCondition current = baseCondition()
                .refineType("RESET")
                .priceMax(null)
                .excludeTerms(List.of())
                .build();

        ProductQueryCondition merged = merger.merge(current, previous);

        assertThat(merged.priceMax()).isNull();
        assertThat(merged.excludeTerms()).isEmpty();
    }

    @Test
    void mergeReturnsCurrentWhenPreviousNull() {
        ProductQueryCondition current = baseCondition().refineType("INHERIT").build();
        assertThat(merger.merge(current, null)).isEqualTo(current);
    }

    private static Builder baseCondition() {
        return new Builder();
    }

    private static class Builder {
        private String refineType = "RESET";
        private BigDecimal priceMin;
        private BigDecimal priceMax;
        private List<String> includeTerms = List.of();
        private List<String> excludeTerms = List.of();
        private ProductAttributesCondition attributes = ProductAttributesCondition.empty();

        Builder refineType(String value) {
            this.refineType = value;
            return this;
        }

        Builder priceMin(BigDecimal value) {
            this.priceMin = value;
            return this;
        }

        Builder priceMax(BigDecimal value) {
            this.priceMax = value;
            return this;
        }

        Builder includeTerms(List<String> value) {
            this.includeTerms = value;
            return this;
        }

        Builder excludeTerms(List<String> value) {
            this.excludeTerms = value;
            return this;
        }

        Builder attributes(ProductAttributesCondition value) {
            this.attributes = value;
            return this;
        }

        ProductQueryCondition build() {
            return new ProductQueryCondition(
                    "raw", "raw", "QUERY", "HYBRID", "kw", "sem",
                    List.of(), List.of(), List.of(), List.of(),
                    includeTerms, excludeTerms,
                    attributes,
                    priceMin, priceMax, null,
                    "RELEVANCE", refineType, List.of(),
                    false, 0.9d, false, List.of()
            );
        }
    }
}
