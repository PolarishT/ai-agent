package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.filter.PostgresFilterFragment;
import com.bytedance.ai.graph.product.retrieval.filter.ProductPostgresFilterBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingSearcherTests {

    private NamedParameterJdbcTemplate jdbc;
    private ProductPostgresFilterBuilder filterBuilder;
    private MarketingSearcher searcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        filterBuilder = mock(ProductPostgresFilterBuilder.class);
        when(filterBuilder.build(any(ProductQueryCondition.class))).thenReturn(PostgresFilterFragment.empty());
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        searcher = new MarketingSearcher(jdbc, filterBuilder);
    }

    @Test
    void sqlQueriesCatalogProductKnowledgeNotRagChunks() {
        searcher.search("有什么卖点", ProductQueryCondition.empty("有什么卖点"), 5);
        String sql = capturedSql();
        assertThat(sql).contains("catalog_product_knowledge");
        assertThat(sql).contains("FROM catalog_product");
        assertThat(sql).doesNotContain("rag_chunks");
        assertThat(sql).doesNotContain("rag_documents");
    }

    @Test
    void sqlDoesNotReadStockOrPriceFromKnowledgeTable() {
        // MARKETING 只允许补充稳定营销文本；不允许从 knowledge 决定库存 / 价格 / 上下架。
        searcher.search("推荐理由", ProductQueryCondition.empty("推荐理由"), 5);
        String sql = capturedSql().toLowerCase();
        // 用 word boundary 正则避免 s_mk.status 这类 SKU 别名子串误命中
        assertThat(sql).doesNotContainPattern("\\bk\\.stock\\b");
        assertThat(sql).doesNotContainPattern("\\bk\\.price\\b");
        assertThat(sql).doesNotContainPattern("\\bk\\.status\\b");
        // 返回的事实字段（product_id / title / brand / category）仍来自 catalog_product
        assertThat(sql).contains("p.id");
        assertThat(sql).contains("p.title");
    }

    @Test
    void recommendReasonScenarioFiltersOnKnowledgeContent() {
        searcher.search("适合什么场景", ProductQueryCondition.empty("适合什么场景"), 5);
        String sql = capturedSql();
        assertThat(sql).contains("k.content");
    }

    @SuppressWarnings("unchecked")
    private String capturedSql() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        return sqlCaptor.getValue();
    }
}
