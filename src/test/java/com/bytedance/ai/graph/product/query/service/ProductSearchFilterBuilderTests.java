package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.AttributeIncludeExclude;
import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.ProductHardFilter;
import com.bytedance.ai.shared.properties.RagProperties;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSearchFilterBuilderTests {

    private final ProductSearchFilterBuilder builder = new ProductSearchFilterBuilder(RagProperties.defaults());

    @Test
    void mapsPriceCategoryBrandAndExcludes() {
        ProductQueryCondition condition = new ProductQueryCondition(
                "raw", "raw", "QUERY", "HYBRID", "kw", "sem",
                List.of("电子产品"),
                List.of(),
                List.of("Sony"),
                List.of(),
                List.of("蓝牙"),
                List.of("二手"),
                new ProductAttributesCondition(
                        new AttributeIncludeExclude(List.of("白色"), List.of("黑色")),
                        new AttributeIncludeExclude(List.of("L"), List.of()),
                        new AttributeIncludeExclude(List.of("棉"), List.of("塑料")),
                        "500ml"
                ),
                new BigDecimal("100"),
                new BigDecimal("300"),
                null,
                "RELEVANCE", "RESET", List.of(),
                false, 0.9d, false, List.of()
        );

        ProductHardFilter filter = builder.build(condition);

        assertThat(filter.priceMin()).isEqualByComparingTo("100");
        assertThat(filter.priceMax()).isEqualByComparingTo("300");
        assertThat(filter.categories()).containsExactly("电子产品");
        assertThat(filter.brands()).containsExactly("Sony");
        assertThat(filter.includeTerms()).contains("蓝牙", "白色", "棉", "500ml");
        assertThat(filter.excludeTerms()).containsExactly("二手");
        assertThat(filter.excludeColors()).containsExactly("黑色");
        assertThat(filter.excludeMaterials()).containsExactly("塑料");
        assertThat(filter.sizes()).containsExactly("L");
        assertThat(filter.materials()).containsExactly("棉");
        assertThat(filter.mustHaveStock()).isTrue();
    }
}
