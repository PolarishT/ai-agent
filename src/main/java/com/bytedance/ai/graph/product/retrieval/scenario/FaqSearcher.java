package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.graph.product.retrieval.filter.PostgresFilterFragment;
import com.bytedance.ai.graph.product.retrieval.filter.ProductPostgresFilterBuilder;
import com.bytedance.ai.shared.metadata.RagChunkType;
import com.bytedance.ai.shared.support.RagLogHelper;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * FAQ evidence 搜索器：查询 {@code catalog_product_faq}。
 *
 * <p>提供两条独立通道，与 {@link RagChunkType#FAQ_QUERY} / {@link RagChunkType#FAQ_ANSWER} 对应：
 * <ul>
 *   <li>{@link #searchQuestion(String, ProductQueryCondition, int)} —— 匹配 question 字段；</li>
 *   <li>{@link #searchAnswer(String, ProductQueryCondition, int)} —— 匹配 answer 字段。</li>
 * </ul>
 *
 * <p>该搜索器只访问原始业务表，<strong>不</strong>查 {@code rag_chunks} / {@code rag_documents}。
 * 最终回答给用户的 FAQ 内容仍以 {@code catalog_product_faq} 原始 question / answer 为准，
 * chunk 文本不作为事实来源。
 */
@Component
public class FaqSearcher {

    private static final Logger log = LoggerFactory.getLogger(FaqSearcher.class);
    private static final Set<String> ALLOWED_COLUMNS = Set.of("question", "answer");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ProductPostgresFilterBuilder filterBuilder;

    public FaqSearcher(
            NamedParameterJdbcTemplate jdbcTemplate,
            ProductPostgresFilterBuilder filterBuilder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.filterBuilder = filterBuilder;
    }

    public List<ProductSearchHit> searchQuestion(String query, ProductQueryCondition condition, int topK) {
        return doSearch(query, condition, topK, "question", RagChunkType.FAQ_QUERY);
    }

    public List<ProductSearchHit> searchAnswer(String query, ProductQueryCondition condition, int topK) {
        return doSearch(query, condition, topK, "answer", RagChunkType.FAQ_ANSWER);
    }

    private List<ProductSearchHit> doSearch(
            String query,
            ProductQueryCondition condition,
            int topK,
            String column,
            RagChunkType chunkType
    ) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        if (!ALLOWED_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("FAQ column must be question or answer: " + column);
        }
        int effectiveTopK = Math.max(1, topK);
        String sqlTemplate = """
                SELECT p.id        AS product_id,
                       p.title     AS title,
                       p.brand     AS brand,
                       p.category  AS category,
                       p.sub_category AS sub_category,
                       MAX(
                            ts_rank(to_tsvector('simple', coalesce(f.%1$s, '')),
                                    plainto_tsquery('simple', :query)) * 1.6
                            + CASE WHEN lower(coalesce(f.%1$s, '')) LIKE :likeQuery THEN 0.8 ELSE 0 END
                       ) AS score
                  FROM catalog_product p
                  JOIN catalog_product_faq f ON f.product_id = p.id
                 WHERE p.status = 'ACTIVE'
                   AND (
                        to_tsvector('simple', coalesce(f.%1$s, '')) @@ plainto_tsquery('simple', :query)
                     OR lower(coalesce(f.%1$s, '')) LIKE :likeQuery
                   )
                """;
        StringBuilder sql = new StringBuilder(String.format(sqlTemplate, column));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("likeQuery", "%" + query.toLowerCase() + "%");

        PostgresFilterFragment fragment = filterBuilder.build(condition);
        if (fragment != null && !fragment.isEmpty()) {
            sql.append(fragment.sql());
            params.addValues(fragment.params());
        }
        sql.append("""
                 GROUP BY p.id, p.title, p.brand, p.category, p.sub_category
                 ORDER BY score DESC, p.id
                 LIMIT :topK
                """);
        params.addValue("topK", effectiveTopK);

        try {
            String renderedSql = sql.toString();
            log.debug("FAQ keyword SQL: column={}, queryPreview={}, sql=[{}], params={}",
                    column,
                    RagLogHelper.previewQuestion(query),
                    EvidenceSearcherLogSupport.flatten(renderedSql),
                    EvidenceSearcherLogSupport.describeParams(params));
            List<ProductSearchHit> hits = jdbcTemplate.query(renderedSql, params,
                    (rs, rowNum) -> EvidenceHitMapper.fromRow(rs, chunkType));
            log.info(
                    "FAQ keyword search done: column={}, chunkType={}, queryPreview={}, topK={}, hitCount={}, hasFilter={}, hits={}",
                    column,
                    chunkType,
                    RagLogHelper.previewQuestion(query),
                    effectiveTopK,
                    hits.size(),
                    fragment != null && !fragment.isEmpty(),
                    EvidenceSearcherLogSupport.summarize(hits)
            );
            return hits;
        } catch (RuntimeException exception) {
            log.warn("FAQ keyword search failed: column={}, queryPreview={}, error={}, sql=[{}], params={}",
                    column, RagLogHelper.previewQuestion(query), RagLogHelper.errorSummary(exception),
                    EvidenceSearcherLogSupport.flatten(sql.toString()),
                    EvidenceSearcherLogSupport.describeParams(params));
            throw exception;
        }
    }
}
