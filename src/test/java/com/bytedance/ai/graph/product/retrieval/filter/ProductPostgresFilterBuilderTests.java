package com.bytedance.ai.graph.product.retrieval.filter;

import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.dictionary.BrandDictionaryService;
import com.bytedance.ai.graph.product.retrieval.dictionary.CategoryDictionaryService;
import com.bytedance.ai.shared.properties.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 直接验证 {@link ProductPostgresFilterBuilder} 的 SQL 文本与参数：
 *
 * <ul>
 *   <li>修复 {@code ILIKE ANY(:xxx) ESCAPE '\'} 语法 bug —— 全局不再出现 ESCAPE 子句；</li>
 *   <li>includeTerms 按 term 拆 AND group，字段之间 OR；</li>
 *   <li>已经在 categoryTerms / brandTerms 出现过的词不再放入软 include 通道；</li>
 *   <li>excludeTerms NOT 覆盖 attributes_json + SKU properties_json。</li>
 * </ul>
 */
class ProductPostgresFilterBuilderTests {

    private ProductPostgresFilterBuilder builder;

    @BeforeEach
    void setUp() {
        RagProperties properties = RagProperties.defaults();
        CategoryDictionaryService categoryDictionary = mock(CategoryDictionaryService.class);
        BrandDictionaryService brandDictionary = mock(BrandDictionaryService.class);
        // 让字典解析返回空，确保走 LIKE pattern 分支
        when(categoryDictionary.resolveIds(anyList())).thenReturn(List.of());
        when(brandDictionary.resolveIds(anyList())).thenReturn(List.of());
        builder = new ProductPostgresFilterBuilder(properties, categoryDictionary, brandDictionary);
    }

    @Test
    void sqlNeverUsesIlikeAnyOrEscape() {
        // PG 不支持 `ILIKE ANY(list)` 在 NamedParameterJdbcTemplate 下传 List
        // （会抛 op ANY/ALL (array) requires array on right side），全局必须改为命名参数展开。
        PostgresFilterFragment fragment = builder.build(fullCondition());
        assertThat(fragment.sql()).doesNotContain("ILIKE ANY(");
        assertThat(fragment.sql()).doesNotContain("ESCAPE '\\'");
        assertThat(fragment.sql()).doesNotContain("ESCAPE '\\\\'");
    }

    @Test
    void categoryIncludeExpandsToNamedParamPerTermWithOrAcrossCategoryAndSubCategory() {
        ProductQueryCondition condition = baseCondition("气泡水")
                .withCategoryTerms(List.of("气泡水"))
                .toCondition();
        PostgresFilterFragment fragment = builder.build(condition);

        assertThat(fragment.sql())
                .contains("p.category ILIKE :categoryIncludePattern0")
                .contains("p.sub_category ILIKE :categoryIncludePattern0")
                .doesNotContain("ILIKE ANY(:categoryIncludePatterns)");
        assertThat(fragment.params())
                .containsEntry("categoryIncludePattern0", "%气泡水%")
                .doesNotContainKey("categoryIncludePatterns");
    }

    @Test
    void multipleCategoryIncludeTermsExpandToMultipleNamedParamsJoinedByOr() {
        // 用户说 "饮料 / 气泡水"，两者作为 category 候选，应该 OR 组合
        ProductQueryCondition condition = baseCondition("饮料 气泡水")
                .withCategoryTerms(List.of("饮料", "气泡水"))
                .toCondition();
        PostgresFilterFragment fragment = builder.build(condition);

        assertThat(fragment.params())
                .containsEntry("categoryIncludePattern0", "%饮料%")
                .containsEntry("categoryIncludePattern1", "%气泡水%");
        assertThat(fragment.sql())
                .contains(":categoryIncludePattern0")
                .contains(":categoryIncludePattern1");
    }

    @Test
    void singleIncludeTermExpandsToOneAndGroupCoveringProductFieldsAndSkuProperties() {
        ProductQueryCondition condition = baseCondition("白葡萄味")
                .withIncludeTerms(List.of("白葡萄味"))
                .toCondition();
        PostgresFilterFragment fragment = builder.build(condition);

        assertThat(fragment.params()).containsKey("includePattern0").doesNotContainKey("includePattern1");
        assertThat(fragment.params().get("includePattern0")).isEqualTo("%白葡萄味%");
        // 字段池：title / brand / category / sub_category / attributes_json / SKU properties_json
        assertThat(fragment.sql())
                .contains("p.title ILIKE :includePattern0")
                .contains("coalesce(p.brand, '') ILIKE :includePattern0")
                .contains("coalesce(p.category, '') ILIKE :includePattern0")
                .contains("coalesce(p.sub_category, '') ILIKE :includePattern0")
                .contains("coalesce(p.attributes_json::text, '') ILIKE :includePattern0")
                .contains("s_inc_0.properties_json::text");
    }

    @Test
    void multipleIncludeTermsExpandIntoSeparateAndGroupsOneParamPerTerm() {
        ProductQueryCondition condition = baseCondition("白葡萄味 低糖")
                .withIncludeTerms(List.of("白葡萄味", "低糖"))
                .toCondition();
        PostgresFilterFragment fragment = builder.build(condition);

        // 两个 term 应该各占一个 AND group + 一个 param
        assertThat(fragment.params())
                .containsEntry("includePattern0", "%白葡萄味%")
                .containsEntry("includePattern1", "%低糖%");
        assertThat(fragment.sql())
                .contains(":includePattern0")
                .contains(":includePattern1");
        // 字段池 OR；术语之间没有合并成一个 ANY
        assertThat(fragment.sql()).doesNotContain("ILIKE ANY(:includePatterns)");
    }

    @Test
    void includeTermsAlreadyInCategoryAreDroppedToAvoidDoubleFiltering() {
        // "白葡萄味的气泡水" 这种场景：气泡水既在 categoryTerms 又在 includeTerms
        // builder 应该把 includeTerms 里的 "气泡水" 剔除，只留 "白葡萄味"
        ProductQueryCondition condition = baseCondition("白葡萄味 气泡水")
                .withCategoryTerms(List.of("气泡水"))
                .withIncludeTerms(List.of("白葡萄味", "气泡水"))
                .toCondition();
        PostgresFilterFragment fragment = builder.build(condition);

        assertThat(fragment.params())
                .containsEntry("includePattern0", "%白葡萄味%")
                .doesNotContainKey("includePattern1"); // "气泡水" 不应作为第二个软 include 出现
    }

    @Test
    void includeTermsAlreadyInBrandAreAlsoDropped() {
        ProductQueryCondition condition = baseCondition("元气森林 清爽")
                .withBrandTerms(List.of("元气森林"))
                .withIncludeTerms(List.of("元气森林", "清爽"))
                .toCondition();
        PostgresFilterFragment fragment = builder.build(condition);

        assertThat(fragment.params())
                .containsEntry("includePattern0", "%清爽%")
                .doesNotContainKey("includePattern1");
    }

    @Test
    void excludeTermsNotCoversTitleCategoryBrandAttributesAndSkuProperties() {
        ProductQueryCondition condition = baseCondition("饮料")
                .withExcludeTerms(List.of("苏打水"))
                .toCondition();
        PostgresFilterFragment fragment = builder.build(condition);

        String sql = fragment.sql();
        assertThat(sql).contains("AND NOT (");
        assertThat(sql).contains("coalesce(p.title, '') ILIKE :excludePattern0");
        assertThat(sql).contains("coalesce(p.category, '') ILIKE :excludePattern0");
        assertThat(sql).contains("coalesce(p.sub_category, '') ILIKE :excludePattern0");
        assertThat(sql).contains("coalesce(p.brand, '') ILIKE :excludePattern0");
        assertThat(sql).contains("coalesce(p.attributes_json::text, '') ILIKE :excludePattern0");
        assertThat(sql).contains("s_exc.properties_json::text");
        assertThat(sql).doesNotContain("ILIKE ANY(");
        assertThat(fragment.params())
                .containsEntry("excludePattern0", "%苏打水%")
                .doesNotContainKey("excludePatterns");
    }

    @Test
    void multipleExcludeTermsExpandToCrossProductOfFieldsAndParams() {
        ProductQueryCondition condition = baseCondition("饮料")
                .withExcludeTerms(List.of("苏打水", "可乐"))
                .toCondition();
        PostgresFilterFragment fragment = builder.build(condition);

        assertThat(fragment.params())
                .containsEntry("excludePattern0", "%苏打水%")
                .containsEntry("excludePattern1", "%可乐%");
        assertThat(fragment.sql())
                .contains("ILIKE :excludePattern0")
                .contains("ILIKE :excludePattern1")
                .doesNotContain("ILIKE ANY(");
    }

    @Test
    void recommendScenarioForWhiteGrapeSparklingWaterEmitsExpectedNamedParams() {
        // 输入：推荐一款白葡萄味的气泡水
        ProductQueryCondition condition = baseCondition("白葡萄味 气泡水")
                .withCategoryTerms(List.of("气泡水"))
                .withIncludeTerms(List.of("白葡萄味"))
                .withMustHaveStock(Boolean.TRUE)
                .toCondition();
        PostgresFilterFragment fragment = builder.build(condition);

        String sql = fragment.sql();
        // 不应再出现 ILIKE ANY / ESCAPE
        assertThat(sql).doesNotContain("ILIKE ANY(");
        assertThat(sql).doesNotContain("ESCAPE");
        // category 走 :categoryIncludePattern0
        assertThat(sql).contains(":categoryIncludePattern0");
        // 软 include 走 :includePattern0
        assertThat(sql).contains(":includePattern0");
        // 库存硬过滤
        assertThat(sql).contains("s.stock > 0");
        assertThat(fragment.params())
                .containsEntry("categoryIncludePattern0", "%气泡水%")
                .containsEntry("includePattern0", "%白葡萄味%");
    }

    @Test
    void mustHaveStockPushesActiveSkuStockExists() {
        ProductQueryCondition condition = baseCondition("饮料")
                .withMustHaveStock(Boolean.TRUE)
                .toCondition();
        PostgresFilterFragment fragment = builder.build(condition);

        assertThat(fragment.sql())
                .contains("FROM catalog_sku s")
                .contains("s.status = 'ACTIVE'")
                .contains("s.stock > 0");
    }

    private static ProductQueryCondition fullCondition() {
        return new ConditionBuilder("我想要柠檬味的饮料，不要苏打水，要气泡水")
                .withCategoryTerms(List.of("饮料", "气泡水"))
                .withIncludeTerms(List.of("柠檬味"))
                .withExcludeTerms(List.of("苏打水"))
                .withBrandTerms(List.of("元气森林"))
                .withMustHaveStock(Boolean.TRUE)
                .toCondition();
    }

    private static ConditionBuilder baseCondition(String raw) {
        return new ConditionBuilder(raw);
    }

    /**
     * 简单的 ProductQueryCondition 链式构造器，仅供本测试类使用。
     */
    private static final class ConditionBuilder {
        private final String raw;
        private List<String> categoryTerms = List.of();
        private List<String> brandTerms = List.of();
        private List<String> includeTerms = List.of();
        private List<String> excludeTerms = List.of();
        private Boolean mustHaveStock;

        ConditionBuilder(String raw) {
            this.raw = raw;
        }

        ConditionBuilder withCategoryTerms(List<String> v) { this.categoryTerms = v; return this; }
        ConditionBuilder withBrandTerms(List<String> v) { this.brandTerms = v; return this; }
        ConditionBuilder withIncludeTerms(List<String> v) { this.includeTerms = v; return this; }
        ConditionBuilder withExcludeTerms(List<String> v) { this.excludeTerms = v; return this; }
        ConditionBuilder withMustHaveStock(Boolean v) { this.mustHaveStock = v; return this; }

        ProductQueryCondition toCondition() {
            return new ProductQueryCondition(
                    raw, raw, "QUERY", "HYBRID", raw, raw,
                    categoryTerms, List.of(),
                    brandTerms, List.of(),
                    includeTerms, excludeTerms,
                    ProductAttributesCondition.empty(),
                    (BigDecimal) null, (BigDecimal) null,
                    mustHaveStock,
                    "RELEVANCE", "RESET",
                    List.of(),
                    false, 0.9, false, List.of()
            );
        }
    }
}
