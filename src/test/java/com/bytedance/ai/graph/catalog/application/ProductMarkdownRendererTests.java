package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogProductCreateRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMarkdownRendererTests {

    private final ProductMarkdownRenderer renderer = new ProductMarkdownRenderer();

    @Test
    void renderProfileDoesNotIncludeStockOrRealtimePrice() {
        CatalogProductCreateRequest item = new CatalogProductCreateRequest(
                "元气森林 白葡萄味 苏打气泡水",
                "元气森林",
                "饮料",
                "气泡水",
                new BigDecimal("4.50"),
                new BigDecimal("3.80"),
                new BigDecimal("5.20"),
                4,
                "/img/yqsl.png",
                Map.of(
                        "flavor", "白葡萄味",
                        "package", "330ml",
                        "description", "0 糖 0 脂的清爽气泡水"
                ),
                Map.of(),
                List.of(
                        new CatalogProductCreateRequest.SkuDraft(0, Map.of("flavor", "白葡萄"),
                                new BigDecimal("3.80"), 2, Map.of()),
                        new CatalogProductCreateRequest.SkuDraft(1, Map.of("flavor", "西柚"),
                                new BigDecimal("3.90"), 2, Map.of())
                ),
                List.of(),
                List.of(),
                List.of()
        );

        String profile = renderer.renderProfile(item);

        assertThat(profile)
                .doesNotContain("库存")
                .doesNotContain("¥")
                .doesNotContain("3.80")
                .doesNotContain("3.90")
                .doesNotContain("4.50");
        assertThat(profile)
                .contains("元气森林 白葡萄味 苏打气泡水")
                .contains("**品牌**：元气森林")
                .contains("**类目**：饮料")
                .contains("**子类目**：气泡水")
                .contains("白葡萄味")
                .contains("0 糖 0 脂的清爽气泡水");
    }

    @Test
    void renderProfileDropsDynamicAttributesEvenIfPresentInJson() {
        CatalogProductCreateRequest item = new CatalogProductCreateRequest(
                "T",
                null,
                "饮料",
                null,
                BigDecimal.ZERO,
                null,
                null,
                0,
                null,
                Map.of(
                        "stock", 99,
                        "price", "12.5",
                        "status", "ACTIVE",
                        "promotionPrice", "9.9",
                        "discountStock", 5,
                        "color", "黑"
                ),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        String profile = renderer.renderProfile(item);

        assertThat(profile)
                .doesNotContain("stock")
                .doesNotContain("price")
                .doesNotContain("status")
                .doesNotContain("promotionPrice")
                .doesNotContain("discountStock")
                .doesNotContain("99")
                .doesNotContain("12.5")
                .doesNotContain("9.9");
        assertThat(profile).contains("color");
    }

    @Test
    void renderProfileWritesSkuSpecsButOmitsSkuPriceAndStock() {
        CatalogProductCreateRequest item = new CatalogProductCreateRequest(
                "气泡水",
                "元气森林",
                "饮料",
                "气泡水",
                BigDecimal.ZERO,
                null,
                null,
                0,
                null,
                Map.of(),
                Map.of(),
                List.of(
                        new CatalogProductCreateRequest.SkuDraft(0, Map.of("flavor", "白葡萄"),
                                new BigDecimal("3.80"), 50, Map.of())
                ),
                List.of(),
                List.of(),
                List.of()
        );

        String profile = renderer.renderProfile(item);

        assertThat(profile).contains("flavor=白葡萄");
        assertThat(profile)
                .doesNotContain("¥3.80")
                .doesNotContain("50")
                .doesNotContain("库存");
    }
}
