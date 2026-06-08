package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.graph.product.retrieval.filter.PostgresFilterFragment;
import com.bytedance.ai.graph.product.retrieval.filter.ProductPostgresFilterBuilder;
import com.bytedance.ai.shared.metadata.RagChunkType;
import com.bytedance.ai.shared.support.RagLogHelper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * REVIEW evidence 搜索器：查询 {@code catalog_product_review}。
 *
 * <p>用户问「评价怎么样」「有没有人说太甜」等场景调度本搜索器。最终展示时仍以原始 review
 * 记录（rating / sentiment / created_at / helpful_count）为准，chunk 文本不作为事实来源。
 *
 * <p>该搜索器只访问原始业务表，<strong>不</strong>查 {@code rag_chunks} / {@code rag_documents}。
 */
@Component
public class ReviewSearcher {

    private static final Logger log = LoggerFactory.getLogger(ReviewSearcher.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ProductPostgresFilterBuilder filterBuilder;

    public ReviewSearcher(
            NamedParameterJdbcTemplate jdbcTemplate,
            ProductPostgresFilterBuilder filterBuilder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.filterBuilder = filterBuilder;
    }

    public List<ProductSearchHit> search(String query, ProductQueryCondition condition, int topK) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        int effectiveTopK = Math.max(1, topK);
        StringBuilder sql = new StringBuilder("""
                SELECT p.id        AS product_id,
                       p.title     AS title,
                       p.brand     AS brand,
                       p.category  AS category,
                       p.sub_category AS sub_category,
                       MAX(
                            ts_rank(to_tsvector('simple'::regconfig, coalesce(r.content, ''::text)),
                                    plainto_tsquery('simple'::regconfig, :query))
                            + CASE WHEN lower(coalesce(r.content, ''::text)) LIKE :likeQuery THEN 0.6 ELSE 0 END
                       ) AS score
                  FROM catalog_product p
                  JOIN catalog_product_review r ON r.product_id = p.id
                 WHERE p.status = 'ACTIVE'
                   AND (
                        to_tsvector('simple'::regconfig, coalesce(r.content, ''::text))
                            @@ plainto_tsquery('simple'::regconfig, :query)
                     OR lower(coalesce(r.content, ''::text)) LIKE :likeQuery
                   )
                """);
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
            log.debug("REVIEW keyword SQL: queryPreview={}, sql=[{}], params={}",
                    RagLogHelper.previewQuestion(query),
                    EvidenceSearcherLogSupport.flatten(renderedSql),
                    EvidenceSearcherLogSupport.describeParams(params));
            List<ProductSearchHit> hits = jdbcTemplate.query(renderedSql, params,
                    (rs, rowNum) -> EvidenceHitMapper.fromRow(rs, RagChunkType.REVIEW));
            log.info(
                    "REVIEW keyword search done: queryPreview={}, topK={}, hitCount={}, hasFilter={}, hits={}",
                    RagLogHelper.previewQuestion(query),
                    effectiveTopK,
                    hits.size(),
                    fragment != null && !fragment.isEmpty(),
                    EvidenceSearcherLogSupport.summarize(hits)
            );
            return hits;
        } catch (RuntimeException exception) {
            log.warn("REVIEW keyword search failed: queryPreview={}, error={}, sql=[{}], params={}",
                    RagLogHelper.previewQuestion(query),
                    RagLogHelper.errorSummary(exception),
                    EvidenceSearcherLogSupport.flatten(sql.toString()),
                    EvidenceSearcherLogSupport.describeParams(params));
            throw exception;
        }
    }
}
