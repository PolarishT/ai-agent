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
 * PRODUCT_PROFILE evidence 搜索器：查询 {@code catalog_product} JOIN {@code catalog_sku}。
 *
 * <p>这是商品检索 / 推荐 / 库存 / 价格场景的主表搜索器，所有动态业务事实（status / stock / price）
 * 都从这里取，不会从 chunk_text 解析。
 *
 * <h2>子句分工</h2>
 * <ul>
 *   <li>主 SQL 提供「{@code :query} 命中任意字段才召回」兜底，并按命中字段计算 ts_rank 加 LIKE 加成；</li>
 *   <li>{@link ProductPostgresFilterBuilder} 推下硬过滤：category / brand / 价格 / 库存 / SKU active /
 *       includeTerms（按 term AND，每个 term 内字段 OR）/ excludeTerms NOT；</li>
 *   <li>SKU {@code properties_json} 用 JSONB::text 模糊匹配，覆盖口味 / 容量等结构化属性。</li>
 * </ul>
 *
 * <h2>评分权重（按 spec）</h2>
 * <ul>
 *   <li>title 命中：高分（+1.8）</li>
 *   <li>SKU properties_json 命中：高分（+1.5，与 title 相当，弥补 catalog_sku 无 title 列）</li>
 *   <li>category / sub_category 命中：中高分（+1.0）</li>
 *   <li>brand 命中：中分（+0.6）</li>
 *   <li>attributes_json 命中：中低分（+0.4，承担 description 角色）</li>
 * </ul>
 *
 * <p>该搜索器只访问原始业务表，<strong>不</strong>查 {@code rag_chunks} / {@code rag_documents}。
 */
@Component
public class ProductProfileSearcher {

    private static final Logger log = LoggerFactory.getLogger(ProductProfileSearcher.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ProductPostgresFilterBuilder filterBuilder;

    public ProductProfileSearcher(
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
                       (
                            ts_rank(to_tsvector('simple',
                                                coalesce(p.title, '') || ' ' ||
                                                coalesce(p.brand, '') || ' ' ||
                                                coalesce(p.category, '') || ' ' ||
                                                coalesce(p.sub_category, '') || ' ' ||
                                                coalesce(p.attributes_json::text, '')),
                                    plainto_tsquery('simple', :query)) * 2.0
                            + CASE WHEN lower(coalesce(p.title, '')) LIKE :likeQuery THEN 1.8 ELSE 0 END
                            + CASE WHEN EXISTS (
                                  SELECT 1 FROM catalog_sku s_score
                                   WHERE s_score.product_id = p.id
                                     AND s_score.status = 'ACTIVE'
                                     AND lower(coalesce(s_score.properties_json::text, '')) LIKE :likeQuery
                              ) THEN 1.5 ELSE 0 END
                            + CASE WHEN lower(coalesce(p.category, '')) LIKE :likeQuery THEN 1.0 ELSE 0 END
                            + CASE WHEN lower(coalesce(p.sub_category, '')) LIKE :likeQuery THEN 1.0 ELSE 0 END
                            + CASE WHEN lower(coalesce(p.brand, '')) LIKE :likeQuery THEN 0.6 ELSE 0 END
                            + CASE WHEN lower(coalesce(p.attributes_json::text, '')) LIKE :likeQuery THEN 0.4 ELSE 0 END
                       ) AS score
                  FROM catalog_product p
                 WHERE p.status = 'ACTIVE'
                   AND EXISTS (
                        SELECT 1 FROM catalog_sku s_active
                         WHERE s_active.product_id = p.id
                           AND s_active.status = 'ACTIVE'
                   )
                   AND (
                        to_tsvector('simple',
                                    coalesce(p.title, '') || ' ' ||
                                    coalesce(p.brand, '') || ' ' ||
                                    coalesce(p.category, '') || ' ' ||
                                    coalesce(p.sub_category, '') || ' ' ||
                                    coalesce(p.attributes_json::text, '')) @@ plainto_tsquery('simple', :query)
                     OR lower(coalesce(p.title, '')) LIKE :likeQuery
                     OR lower(coalesce(p.brand, '')) LIKE :likeQuery
                     OR lower(coalesce(p.category, '')) LIKE :likeQuery
                     OR lower(coalesce(p.sub_category, '')) LIKE :likeQuery
                     OR lower(coalesce(p.attributes_json::text, '')) LIKE :likeQuery
                     OR EXISTS (
                          SELECT 1 FROM catalog_sku s_match
                           WHERE s_match.product_id = p.id
                             AND s_match.status = 'ACTIVE'
                             AND lower(coalesce(s_match.properties_json::text, '')) LIKE :likeQuery
                       )
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
                 ORDER BY score DESC, p.id
                 LIMIT :topK
                """);
        params.addValue("topK", effectiveTopK);

        try {
            String renderedSql = sql.toString();
            log.debug("PRODUCT_PROFILE keyword SQL: queryPreview={}, sql=[{}], params={}",
                    RagLogHelper.previewQuestion(query),
                    EvidenceSearcherLogSupport.flatten(renderedSql),
                    EvidenceSearcherLogSupport.describeParams(params));
            List<ProductSearchHit> hits = jdbcTemplate.query(renderedSql, params,
                    (rs, rowNum) -> EvidenceHitMapper.fromRow(rs, RagChunkType.PRODUCT_PROFILE));
            log.info(
                    "PRODUCT_PROFILE keyword search done: queryPreview={}, topK={}, hitCount={}, hasFilter={}, hits={}",
                    RagLogHelper.previewQuestion(query),
                    effectiveTopK,
                    hits.size(),
                    fragment != null && !fragment.isEmpty(),
                    EvidenceSearcherLogSupport.summarize(hits)
            );
            return hits;
        } catch (RuntimeException exception) {
            log.warn("PRODUCT_PROFILE keyword search failed: queryPreview={}, error={}, sql=[{}], params={}",
                    RagLogHelper.previewQuestion(query),
                    RagLogHelper.errorSummary(exception),
                    EvidenceSearcherLogSupport.flatten(sql.toString()),
                    EvidenceSearcherLogSupport.describeParams(params));
            throw exception;
        }
    }
}
