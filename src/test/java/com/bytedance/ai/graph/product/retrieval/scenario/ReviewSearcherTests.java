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

class ReviewSearcherTests {

    private NamedParameterJdbcTemplate jdbc;
    private ProductPostgresFilterBuilder filterBuilder;
    private ReviewSearcher searcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        filterBuilder = mock(ProductPostgresFilterBuilder.class);
        when(filterBuilder.build(any(ProductQueryCondition.class))).thenReturn(PostgresFilterFragment.empty());
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        searcher = new ReviewSearcher(jdbc, filterBuilder);
    }

    @Test
    void sqlQueriesCatalogProductReviewNotRagChunks() {
        searcher.search("评价怎么样", ProductQueryCondition.empty("评价怎么样"), 5);
        String sql = capturedSql();
        assertThat(sql).contains("catalog_product_review");
        assertThat(sql).contains("FROM catalog_product");
        assertThat(sql).doesNotContain("rag_chunks");
        assertThat(sql).doesNotContain("rag_documents");
    }

    @Test
    void reviewSqlMatchesReviewContentNotChunkText() {
        searcher.search("有没有人说太甜", ProductQueryCondition.empty("有没有人说太甜"), 5);
        String sql = capturedSql();
        assertThat(sql).contains("r.content");
        assertThat(sql).contains("to_tsvector('simple'::regconfig, coalesce(r.content, ''::text))");
        assertThat(sql).contains("plainto_tsquery('simple'::regconfig, :query)");
        assertThat(sql).contains("lower(coalesce(r.content, ''::text)) LIKE :likeQuery");
        // 不应该用 chunk_text 来 match review 内容
        assertThat(sql).doesNotContain("chunk_text");
    }

    @SuppressWarnings("unchecked")
    private String capturedSql() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        return sqlCaptor.getValue();
    }
}
