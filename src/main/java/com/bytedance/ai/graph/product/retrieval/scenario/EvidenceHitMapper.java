package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.shared.metadata.RagChunkType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把原始 catalog 表的 ResultSet 行映射为 {@link ProductSearchHit} 的小工具。
 *
 * <p>各 searcher 的 SQL 都遵循相同投影约定：
 * {@code product_id / title / brand / category / sub_category / score}。
 * 由本 helper 集中处理 nullable 字段与 metadata 写入。
 */
final class EvidenceHitMapper {

    private EvidenceHitMapper() {
    }

    static ProductSearchHit fromRow(ResultSet rs, RagChunkType chunkType) throws SQLException {
        Long productId = rs.getObject("product_id", Long.class);
        double score = rs.getDouble("score");
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "title", rs.getString("title"));
        putIfPresent(metadata, "brand", rs.getString("brand"));
        putIfPresent(metadata, "category", rs.getString("category"));
        putIfPresent(metadata, "subCategory", rs.getString("sub_category"));
        return new ProductSearchHit(
                productId,
                null,
                productId == null ? null : String.valueOf(productId),
                score,
                chunkType.name(),
                null,
                metadata
        );
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isEmpty()) {
            metadata.put(key, value);
        }
    }
}
