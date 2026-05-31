package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.filter.PostgresFilterFragment;
import com.bytedance.ai.graph.product.retrieval.filter.ProductPostgresFilterBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductProfileSearcherTests {

    private NamedParameterJdbcTemplate jdbc;
    private ProductPostgresFilterBuilder filterBuilder;
    private ProductProfileSearcher searcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        filterBuilder = mock(ProductPostgresFilterBuilder.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        searcher = new ProductProfileSearcher(jdbc, filterBuilder);
    }

    @Test
    void sqlHitsCatalogProductAndCatalogSkuNotRagChunks() {
        when(filterBuilder.build(any(ProductQueryCondition.class))).thenReturn(PostgresFilterFragment.empty());
        searcher.search("饮料", ProductQueryCondition.empty("饮料"), 5);

        String sql = capturedSql();
        assertThat(sql).contains("FROM catalog_product");
        assertThat(sql).contains("catalog_sku");
        assertThat(sql).doesNotContain("rag_chunks");
        assertThat(sql).doesNotContain("rag_documents");
    }

    @Test
    void sqlOnlyReturnsActiveProductsAndActiveSkus() {
        when(filterBuilder.build(any(ProductQueryCondition.class))).thenReturn(PostgresFilterFragment.empty());
        searcher.search("饮料", ProductQueryCondition.empty("饮料"), 5);

        String sql = capturedSql();
        assertThat(sql).contains("p.status = 'ACTIVE'");
        assertThat(sql).contains("s_active.status = 'ACTIVE'");
    }

    @Test
    void sqlNeverEmitsIlikeAnyOrEscape() {
        // PG bug 复现保护：
        //   1) ILIKE ANY(:list) → NamedParameterJdbcTemplate 传 List 时 PG 抛
        //      op ANY/ALL (array) requires array on right side
        //   2) ILIKE ANY(:list) ESCAPE '\' → PG 不支持该组合，抛 syntax error
        when(filterBuilder.build(any(ProductQueryCondition.class))).thenReturn(realLemonFragment());
        searcher.search("柠檬味饮料", lemonSparklingWaterCondition(), 5);

        String sql = capturedSql();
        assertThat(sql).doesNotContain("ILIKE ANY(");
        assertThat(sql).doesNotContain("ESCAPE '\\'");
        assertThat(sql).doesNotContain("ESCAPE '\\\\'");
    }

    @Test
    void whiteGrapeSparklingWaterScenarioPushesCategoryAndSoftIncludeSeparately() {
        // 输入："推荐一款白葡萄味的气泡水"
        // categoryTerms = [气泡水], includeTerms = [白葡萄味]（不重复气泡水）
        ProductQueryCondition condition = whiteGrapeCondition();
        when(filterBuilder.build(any(ProductQueryCondition.class))).thenReturn(realWhiteGrapeFragment());

        searcher.search("白葡萄味 气泡水", condition, 5);

        // PostgresFilterBuilder 收到原样 condition
        ArgumentCaptor<ProductQueryCondition> conditionCaptor = ArgumentCaptor.forClass(ProductQueryCondition.class);
        verify(filterBuilder).build(conditionCaptor.capture());
        ProductQueryCondition pushed = conditionCaptor.getValue();
        assertThat(pushed.categoryTerms()).contains("气泡水");
        assertThat(pushed.includeTerms())
                .contains("白葡萄味")
                .doesNotContain("气泡水");  // 软文本通道不重复 category
        assertThat(pushed.mustHaveStock()).isTrue();

        // SQL 命中 catalog 主表，不命中 rag_chunks
        String sql = capturedSql();
        assertThat(sql).contains("FROM catalog_product");
        assertThat(sql).contains("catalog_sku");
        assertThat(sql).doesNotContain("rag_chunks");
        assertThat(sql).doesNotContain("rag_documents");
    }

    @Test
    void lemonSparklingWaterScenarioPushesExcludeAndIncludeTerms() {
        // 输入："我想要柠檬味的饮料，不要苏打水，要气泡水"
        ProductQueryCondition condition = lemonSparklingWaterCondition();
        when(filterBuilder.build(any(ProductQueryCondition.class))).thenReturn(realLemonFragment());

        searcher.search("柠檬 饮料 气泡水", condition, 5);

        String sql = capturedSql();
        assertThat(sql).contains("FROM catalog_product");
        assertThat(sql).contains("catalog_sku");
        assertThat(sql).doesNotContain("rag_chunks");
        assertThat(sql).doesNotContain("rag_documents");
        // SKU active + stock > 0 来自 filter builder
        assertThat(sql).contains("s.stock > 0");

        ArgumentCaptor<ProductQueryCondition> conditionCaptor = ArgumentCaptor.forClass(ProductQueryCondition.class);
        verify(filterBuilder).build(conditionCaptor.capture());
        ProductQueryCondition pushed = conditionCaptor.getValue();
        assertThat(pushed.includeTerms()).contains("柠檬味").doesNotContain("气泡水", "饮料");
        assertThat(pushed.excludeTerms()).containsExactly("苏打水");
    }

    @Test
    void sqlScoreWeightsFollowSpec() {
        when(filterBuilder.build(any(ProductQueryCondition.class))).thenReturn(PostgresFilterFragment.empty());
        searcher.search("饮料", ProductQueryCondition.empty("饮料"), 5);

        String sql = capturedSql();
        // title 权重最高，sku properties 次高，category/sub_category 中高，brand 中，attributes_json 中低
        assertThat(sql).contains("THEN 1.8 ELSE 0 END");   // p.title
        assertThat(sql).contains("THEN 1.5 ELSE 0 END");   // SKU properties_json
        assertThat(sql).contains("THEN 1.0 ELSE 0 END");   // category / sub_category
        assertThat(sql).contains("THEN 0.6 ELSE 0 END");   // brand
        assertThat(sql).contains("THEN 0.4 ELSE 0 END");   // attributes_json
    }

    @SuppressWarnings("unchecked")
    private String capturedSql() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        return sqlCaptor.getValue();
    }

    private PostgresFilterFragment realLemonFragment() {
        // 模拟新版 ProductPostgresFilterBuilder 输出：所有多值条件都用动态命名参数展开，无 ANY()。
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("includePattern0", "%柠檬味%")
                .addValue("excludePattern0", "%苏打水%")
                .addValue("categoryIncludePattern0", "%饮料%")
                .addValue("categoryIncludePattern1", "%气泡水%");
        return new PostgresFilterFragment(
                """
                AND EXISTS (
                    SELECT 1 FROM catalog_sku s
                     WHERE s.product_id = p.id
                       AND s.status = 'ACTIVE'
                       AND s.stock > 0
                )
                AND (
                    (
                        p.category ILIKE :categoryIncludePattern0
                     OR p.sub_category ILIKE :categoryIncludePattern0
                    )
                    OR
                    (
                        p.category ILIKE :categoryIncludePattern1
                     OR p.sub_category ILIKE :categoryIncludePattern1
                    )
                )
                AND (
                    p.title ILIKE :includePattern0
                 OR coalesce(p.brand, '') ILIKE :includePattern0
                )
                AND NOT (
                    coalesce(p.title, '') ILIKE :excludePattern0
                 OR coalesce(p.category, '') ILIKE :excludePattern0
                )
                """,
                params.getValues()
        );
    }

    private PostgresFilterFragment realWhiteGrapeFragment() {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("includePattern0", "%白葡萄味%")
                .addValue("categoryIncludePattern0", "%气泡水%");
        return new PostgresFilterFragment(
                """
                AND EXISTS (
                    SELECT 1 FROM catalog_sku s
                     WHERE s.product_id = p.id
                       AND s.status = 'ACTIVE'
                       AND s.stock > 0
                )
                AND (
                    (
                        p.category ILIKE :categoryIncludePattern0
                     OR p.sub_category ILIKE :categoryIncludePattern0
                    )
                )
                AND (
                    p.title ILIKE :includePattern0
                 OR coalesce(p.brand, '') ILIKE :includePattern0
                )
                """,
                params.getValues()
        );
    }

    private ProductQueryCondition whiteGrapeCondition() {
        return new ProductQueryCondition(
                "推荐一款白葡萄味的气泡水",
                "白葡萄味 气泡水",
                "RECOMMEND",
                "HYBRID",
                "白葡萄味 气泡水",
                "白葡萄味的气泡水",
                List.of("气泡水"),
                List.of(),
                List.of(),
                List.of(),
                List.of("白葡萄味"),  // 注意：不重复 "气泡水"
                List.of(),
                ProductAttributesCondition.empty(),
                null,
                null,
                Boolean.TRUE,
                "RELEVANCE",
                "RESET",
                List.of(),
                false,
                0.9,
                false,
                List.of()
        );
    }

    private ProductQueryCondition lemonSparklingWaterCondition() {
        return new ProductQueryCondition(
                "我想要柠檬味的饮料，不要苏打水，要气泡水",
                "柠檬 饮料 气泡水 不要苏打水",
                "QUERY",
                "HYBRID",
                "柠檬味 饮料 气泡水",
                "柠檬味的气泡水饮料",
                List.of("饮料", "气泡水"),  // category 硬过滤
                List.of(),
                List.of(),
                List.of(),
                List.of("柠檬味"),  // 软 include，不重复 category
                List.of("苏打水"),
                ProductAttributesCondition.empty(),
                null,
                null,
                Boolean.TRUE,
                "RELEVANCE",
                "RESET",
                List.of(),
                false,
                0.9,
                false,
                List.of()
        );
    }
}
