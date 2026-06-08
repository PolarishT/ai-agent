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
 * MARKETING evidence 搜索器：查询 {@code catalog_product_knowledge}（卖点 / 推荐理由 / 适用场景 /
 * 口感描述 / 使用指南 / 评价总结）。
 *
 * <h2>核心约束</h2>
 * <ul>
 *   <li>MARKETING 只补充稳定营销文本，不决定库存 / 价格 / 上架状态；库存与价格永远来自
 *       {@code catalog_sku}；</li>
 *   <li>SQL 强制 {@code p.status = 'ACTIVE'} 与 {@code EXISTS active SKU}，
 *       让 marketing 命中的商品至少有可售 SKU，避免「PROFILE 没召回但 MARKETING 强推一个不可售商品」；</li>
 *   <li>{@link ProductPostgresFilterBuilder} 推下的库存 / 价格 / category 硬过滤同样应用，
 *       让 MARKETING 与 PROFILE 在硬条件上保持一致。</li>
 * </ul>
 *
 * <h2>评分权重</h2>
 * <ul>
 *   <li>knowledge.content 命中：ts_rank × 1.0 + LIKE +0.6</li>
 *   <li>knowledge.title 命中：ts_rank × 0.6 + LIKE +0.4</li>
 * </ul>
 * 总分上限约 2.0，低于 ProductProfileSearcher 的 title 命中（ts_rank × 2.0 + 1.8）。
 *
 * <p>该搜索器只访问原始业务表，<strong>不</strong>查 {@code rag_chunks} / {@code rag_documents}。
 */
@Component
public class MarketingSearcher {

    private static final Logger log = LoggerFactory.getLogger(MarketingSearcher.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ProductPostgresFilterBuilder filterBuilder;

    public MarketingSearcher(
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
                            ts_rank(to_tsvector('simple'::regconfig, coalesce(k.content, ''::text)),
                                    plainto_tsquery('simple'::regconfig, :query)) * 1.0
                            + ts_rank(to_tsvector('simple'::regconfig, coalesce(k.title, ''::text)),
                                      plainto_tsquery('simple'::regconfig, :query)) * 0.6
                            + CASE WHEN lower(coalesce(k.content, ''::text)) LIKE :likeQuery THEN 0.6 ELSE 0 END
                            + CASE WHEN lower(coalesce(k.title, ''::text)) LIKE :likeQuery THEN 0.4 ELSE 0 END
                       ) AS score
                  FROM catalog_product p
                  JOIN catalog_product_knowledge k ON k.product_id = p.id
                 WHERE p.status = 'ACTIVE'
                   AND EXISTS (
                        SELECT 1 FROM catalog_sku s_mk
                         WHERE s_mk.product_id = p.id
                           AND s_mk.status = 'ACTIVE'
                   )
                   AND (
                        to_tsvector('simple'::regconfig, coalesce(k.content, ''::text) || ' ' || coalesce(k.title, ''::text))
                            @@ plainto_tsquery('simple'::regconfig, :query)
                     OR lower(coalesce(k.content, ''::text)) LIKE :likeQuery
                     OR lower(coalesce(k.title, ''::text)) LIKE :likeQuery
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
            log.debug("MARKETING keyword SQL: queryPreview={}, sql=[{}], params={}",
                    RagLogHelper.previewQuestion(query),
                    EvidenceSearcherLogSupport.flatten(renderedSql),
                    EvidenceSearcherLogSupport.describeParams(params));
            List<ProductSearchHit> hits = jdbcTemplate.query(renderedSql, params,
                    (rs, rowNum) -> EvidenceHitMapper.fromRow(rs, RagChunkType.MARKETING));
            log.info(
                    "MARKETING keyword search done: queryPreview={}, topK={}, hitCount={}, hasFilter={}, hits={}",
                    RagLogHelper.previewQuestion(query),
                    effectiveTopK,
                    hits.size(),
                    fragment != null && !fragment.isEmpty(),
                    EvidenceSearcherLogSupport.summarize(hits)
            );
            return hits;
        } catch (RuntimeException exception) {
            log.warn("MARKETING keyword search failed: queryPreview={}, error={}, sql=[{}], params={}",
                    RagLogHelper.previewQuestion(query),
                    RagLogHelper.errorSummary(exception),
                    EvidenceSearcherLogSupport.flatten(sql.toString()),
                    EvidenceSearcherLogSupport.describeParams(params));
            throw exception;
        }
    }
}
