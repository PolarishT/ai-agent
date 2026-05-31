package com.bytedance.ai.graph.product.query;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品对比结果。
 *
 * @param dimensions 对比维度名（title / brand / price / color / capacity / stock / matchReason 等）
 * @param rows       每个候选商品一行
 * @param summary    对比摘要（中文，给用户看）
 */
public record ProductComparisonResult(
        List<String> dimensions,
        List<Row> rows,
        String summary
) {

    public ProductComparisonResult {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public record Row(
            int index,
            Long productId,
            String externalRef,
            String title,
            String brand,
            BigDecimal price,
            String color,
            String capacity,
            Integer stock,
            List<String> matchReasons
    ) {
        public Row {
            matchReasons = matchReasons == null ? List.of() : List.copyOf(matchReasons);
        }
    }
}
