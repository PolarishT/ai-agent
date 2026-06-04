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
        String summary,
        List<ProductColumn> products,
        List<DimensionRow> dimensionRows,
        Decision decision,
        List<String> caveats
) {

    public ProductComparisonResult {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        rows = rows == null ? List.of() : List.copyOf(rows);
        products = products == null ? List.of() : List.copyOf(products);
        dimensionRows = dimensionRows == null ? List.of() : List.copyOf(dimensionRows);
        decision = decision == null ? Decision.empty() : decision;
        caveats = caveats == null ? List.of() : List.copyOf(caveats);
    }

    public ProductComparisonResult(
            List<String> dimensions,
            List<Row> rows,
            String summary
    ) {
        this(dimensions, rows, summary, List.of(), List.of(), Decision.empty(), List.of());
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

    public record ProductColumn(
            int index,
            Long productId,
            String externalRef,
            String title,
            String brand,
            BigDecimal price
    ) {
    }

    public record DimensionRow(
            String key,
            String label,
            List<Cell> cells,
            Integer winnerIndex,
            String reason
    ) {
        public DimensionRow {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    public record Cell(
            int productIndex,
            String value,
            boolean missing,
            List<String> evidence
    ) {
        public Cell {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record Decision(
            Integer recommendedIndex,
            String recommendation,
            List<String> reasons,
            List<String> tradeoffs
    ) {
        public Decision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            tradeoffs = tradeoffs == null ? List.of() : List.copyOf(tradeoffs);
        }

        public static Decision empty() {
            return new Decision(null, "", List.of(), List.of());
        }
    }
}
