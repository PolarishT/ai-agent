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

class FaqSearcherTests {

    private NamedParameterJdbcTemplate jdbc;
    private ProductPostgresFilterBuilder filterBuilder;
    private FaqSearcher searcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        filterBuilder = mock(ProductPostgresFilterBuilder.class);
        when(filterBuilder.build(any(ProductQueryCondition.class))).thenReturn(PostgresFilterFragment.empty());
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        searcher = new FaqSearcher(jdbc, filterBuilder);
    }

    @Test
    void searchQuestionHitsFaqQuestionColumnNotRagChunks() {
        searcher.searchQuestion("保质期多久", ProductQueryCondition.empty("保质期多久"), 5);
        String sql = capturedSql();
        assertThat(sql).contains("catalog_product_faq");
        assertThat(sql).contains("f.question");
        assertThat(sql).doesNotContain("f.answer");
        assertThat(sql).doesNotContain("rag_chunks");
    }

    @Test
    void searchAnswerHitsFaqAnswerColumnNotQuestion() {
        searcher.searchAnswer("常温保存", ProductQueryCondition.empty("常温保存"), 5);
        String sql = capturedSql();
        assertThat(sql).contains("catalog_product_faq");
        assertThat(sql).contains("f.answer");
        assertThat(sql).doesNotContain("f.question");
        assertThat(sql).doesNotContain("rag_chunks");
    }

    @Test
    void faqJoinsCatalogProductForBaseFacts() {
        searcher.searchQuestion("怎么保存", ProductQueryCondition.empty("怎么保存"), 5);
        String sql = capturedSql();
        assertThat(sql).contains("FROM catalog_product");
        assertThat(sql).contains("p.title");
    }

    @SuppressWarnings("unchecked")
    private String capturedSql() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        return sqlCaptor.getValue();
    }
}
