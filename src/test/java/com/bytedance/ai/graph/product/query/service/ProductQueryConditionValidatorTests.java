package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.AttributeIncludeExclude;
import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductQueryConditionValidatorTests {

    private final ProductQueryConditionValidator validator = new ProductQueryConditionValidator();

    @Test
    void swapsPriceWhenMinAboveMax() {
        ProductQueryCondition input = condition(new BigDecimal("500"), new BigDecimal("300"), List.of(), List.of(), 0.9d);

        ProductQueryCondition result = validator.validate(input);

        assertThat(result.priceMin()).isEqualByComparingTo("300");
        assertThat(result.priceMax()).isEqualByComparingTo("500");
    }

    @Test
    void nullsNegativePriceAndRecordsMissingSlot() {
        ProductQueryCondition input = condition(new BigDecimal("-10"), null, List.of(), List.of(), 0.9d);

        ProductQueryCondition result = validator.validate(input);

        assertThat(result.priceMin()).isNull();
        assertThat(result.missingSlots()).contains("price");
    }

    @Test
    void excludeOverridesInclude() {
        ProductQueryCondition input = condition(
                null, null,
                List.of("黑色", "蓝牙"),
                List.of("黑色"),
                0.9d
        );

        ProductQueryCondition result = validator.validate(input);

        assertThat(result.includeTerms()).containsExactly("蓝牙");
        assertThat(result.excludeTerms()).containsExactly("黑色");
    }

    @Test
    void attributesExcludeOverridesInclude() {
        ProductQueryCondition input = new ProductQueryCondition(
                "raw", "raw", "QUERY", "HYBRID", "raw", "raw",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                new ProductAttributesCondition(
                        new AttributeIncludeExclude(List.of("黑色"), List.of("黑色")),
                        AttributeIncludeExclude.empty(),
                        AttributeIncludeExclude.empty(),
                        null
                ),
                null, null, null,
                "RELEVANCE", "RESET", List.of(),
                false, 0.9d, false, List.of()
        );

        ProductQueryCondition result = validator.validate(input);

        assertThat(result.attributes().color().include()).isEmpty();
        assertThat(result.attributes().color().exclude()).containsExactly("黑色");
    }

    @Test
    void lowConfidenceForcesClarify() {
        ProductQueryCondition input = condition(null, null, List.of(), List.of(), 0.2d);

        ProductQueryCondition result = validator.validate(input);

        assertThat(result.confidence()).isEqualTo(0.2d);
        assertThat(result.needClarify()).isTrue();
    }

    @Test
    void confidenceIsClampedTo01() {
        ProductQueryCondition input = condition(null, null, List.of(), List.of(), 5.0d);

        ProductQueryCondition result = validator.validate(input);

        assertThat(result.confidence()).isEqualTo(1.0d);
        assertThat(result.needClarify()).isFalse();
    }

    @Test
    void appliesDefaultsForBlankIntentAndSort() {
        ProductQueryCondition input = new ProductQueryCondition(
                "raw", "raw", "  ", "  ", "raw", "raw",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                ProductAttributesCondition.empty(),
                null, null, null,
                "  ", "  ", List.of(),
                false, 0.9d, false, List.of()
        );

        ProductQueryCondition result = validator.validate(input);

        assertThat(result.intent()).isEqualTo("QUERY");
        assertThat(result.queryMode()).isEqualTo("HYBRID");
        assertThat(result.sort()).isEqualTo("RELEVANCE");
        assertThat(result.refineType()).isEqualTo("RESET");
    }

    private ProductQueryCondition condition(
            BigDecimal priceMin,
            BigDecimal priceMax,
            List<String> includeTerms,
            List<String> excludeTerms,
            double confidence
    ) {
        return new ProductQueryCondition(
                "raw", "raw", "QUERY", "HYBRID", "raw", "raw",
                List.of(), List.of(), List.of(), List.of(),
                includeTerms, excludeTerms,
                ProductAttributesCondition.empty(),
                priceMin, priceMax, null,
                "RELEVANCE", "RESET", List.of(),
                false, confidence, false, List.of()
        );
    }
}
