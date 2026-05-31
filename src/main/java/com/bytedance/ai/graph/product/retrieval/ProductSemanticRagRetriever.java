package com.bytedance.ai.graph.product.retrieval;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.filter.MilvusScalarFilterBuilder;
import com.bytedance.ai.graph.product.retrieval.filter.PostgresFilterFragment;
import com.bytedance.ai.graph.product.retrieval.filter.ProductPostgresFilterBuilder;
import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagLogHelper;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 商品专用语义召回器：
 *
 * <ol>
 *   <li>{@link MilvusScalarFilterBuilder} 按 condition 生成 Milvus scalar filter expression，下推到向量检索请求；</li>
 *   <li>对 Milvus 命中按 vectorId 在 PostgreSQL 侧 JOIN catalog_product 再过滤，
 *       同样的 condition 还会再通过 {@link ProductPostgresFilterBuilder} 转成 SQL 二次校验，
 *       防止 Milvus scalar filter 不完整 / 索引脏数据导致违反业务约束的 product 漏出来。</li>
 * </ol>
 *
 * <p><strong>定位：evidence 召回器，而不是事实表。</strong>本类返回的 {@link ProductSearchHit#productId()}
 * 与 {@link ProductSearchHit#chunkType()} 只表示"语义层命中过哪个 chunk"，最终展示给用户的
 * 商品事实（标题 / 品牌 / 价格 / 库存 / SKU 状态）一律由后续 hydrate 阶段从 catalog_product /
 * catalog_sku 原始表取，参见 {@code ProductRanker.refine(...)} 与 {@code ProductHydrationOptionsResolver}。
 *
 * <p>有别于通用 Milvus retriever —— 后者输出 chunk 级结果供文档问答链路使用；
 * 本类输出 Product 级 {@link ProductSearchHit}，每个 Product 取 Milvus 命中分数最高的那一条 chunk。
 */
@Component
@ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "true")
public class ProductSemanticRagRetriever {

    private static final Logger log = LoggerFactory.getLogger(ProductSemanticRagRetriever.class);

    private final MilvusVectorStore vectorStore;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RagProperties ragProperties;
    private final MilvusScalarFilterBuilder milvusScalarFilterBuilder;
    private final ProductPostgresFilterBuilder postgresFilterBuilder;

    public ProductSemanticRagRetriever(
            MilvusVectorStore vectorStore,
            NamedParameterJdbcTemplate jdbcTemplate,
            RagProperties ragProperties,
            MilvusScalarFilterBuilder milvusScalarFilterBuilder,
            ProductPostgresFilterBuilder postgresFilterBuilder
    ) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.ragProperties = ragProperties;
        this.milvusScalarFilterBuilder = milvusScalarFilterBuilder;
        this.postgresFilterBuilder = postgresFilterBuilder;
    }

    public List<ProductSearchHit> search(String query, ProductQueryCondition condition, int topK) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        int effectiveTopK = Math.max(1, topK);
        int candidateTopK = Math.max(effectiveTopK, ragProperties.productQuery().semanticCandidateTopK());
        String milvusFilter = milvusScalarFilterBuilder.build(condition);

        try {
            SearchRequest.Builder requestBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(candidateTopK)
                    .similarityThreshold(ragProperties.milvus().similarityThreshold());
            if (StringUtils.hasText(milvusFilter)) {
                requestBuilder.filterExpression(milvusFilter);
            }
            List<Document> documents = vectorStore.similaritySearch(requestBuilder.build());
            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            Map<String, Double> scoreByVectorId = new LinkedHashMap<>();
            for (Document document : documents) {
                String vectorId = stringMetadata(document, "vectorId");
                if (StringUtils.hasText(vectorId)) {
                    scoreByVectorId.putIfAbsent(vectorId, document.getScore() == null ? 1.0d : document.getScore());
                }
            }
            if (scoreByVectorId.isEmpty()) {
                log.debug("Product semantic retrieval returned hits without usable vectorId: queryPreview={}",
                        RagLogHelper.previewQuestion(query));
                return List.of();
            }

            List<ProductSearchHit> hits = lookupProductsByVectorIds(scoreByVectorId, condition, effectiveTopK);

            log.debug(
                    "Product semantic retrieval done: queryPreview={}, candidateTopK={}, vectorIds={}, finalHits={}, milvusExpr={}",
                    RagLogHelper.previewQuestion(query),
                    candidateTopK,
                    scoreByVectorId.size(),
                    hits.size(),
                    milvusFilter
            );
            return hits;
        } catch (RuntimeException exception) {
            log.warn("Product semantic retrieval failed: queryPreview={}, milvusExpr={}, error={}",
                    RagLogHelper.previewQuestion(query), milvusFilter, RagLogHelper.errorSummary(exception));
            throw exception;
        }
    }

    private List<ProductSearchHit> lookupProductsByVectorIds(
            Map<String, Double> scoreByVectorId,
            ProductQueryCondition condition,
            int topK
    ) {
        PostgresFilterFragment fragment = postgresFilterBuilder.build(condition, false);
        StringBuilder sql = new StringBuilder("""
                SELECT p.id            AS product_id,
                       p.title         AS title,
                       d.id            AS document_id,
                       c.vector_id     AS vector_id,
                       c.chunk_type    AS chunk_type,
                       c.chunk_text    AS snippet
                  FROM rag_chunks c
                  JOIN rag_documents d ON d.id = c.document_id
                                       AND d.indexed_generation = c.index_generation
                  JOIN catalog_product p ON p.id = c.product_id
                 WHERE p.status = 'ACTIVE'
                   AND c.vector_id IN (:vectorIds)
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("vectorIds", scoreByVectorId.keySet());
        if (!fragment.isEmpty()) {
            sql.append(fragment.sql());
            params.addValues(fragment.params());
        }

        Map<Long, ProductSearchHit> bestByProductId = new LinkedHashMap<>();
        jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> {
            Long productId = rs.getObject("product_id", Long.class);
            Long documentId = rs.getObject("document_id", Long.class);
            String vectorId = rs.getString("vector_id");
            double score = scoreByVectorId.getOrDefault(vectorId, 0.0d);
            ProductSearchHit candidate = new ProductSearchHit(
                    productId, documentId, String.valueOf(productId), score,
                    rs.getString("chunk_type"), rs.getString("snippet"), Map.of()
            );
            ProductSearchHit existing = bestByProductId.get(productId);
            if (existing == null || candidate.score() > existing.score()) {
                bestByProductId.put(productId, candidate);
            }
            return null;
        });

        return bestByProductId.values().stream()
                .sorted(Comparator.comparing(ProductSearchHit::score).reversed()
                        .thenComparing(ProductSearchHit::documentId))
                .limit(topK)
                .toList();
    }

    private String stringMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }
}
