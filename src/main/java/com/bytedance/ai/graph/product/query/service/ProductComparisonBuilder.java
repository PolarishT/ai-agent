package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.ProductComparisonResult;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import com.bytedance.ai.shared.properties.RagProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 对比节点：把若干候选商品按维度并排展示。
 *
 * <p>{@code comparisonTargets} 非空时按 1-based 索引取候选；
 * 否则取前 {@code rag.product-query.comparison.default-top-n} 条。
 */
@Component
public class ProductComparisonBuilder {

    private static final List<String> DIMENSIONS = List.of(
            "title", "brand", "price", "color", "capacity", "stock", "matchReason"
    );

    private final RagProperties ragProperties;

    public ProductComparisonBuilder(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public ProductComparisonResult build(
            List<ProductSearchCandidate> candidates,
            List<Integer> comparisonTargets
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return new ProductComparisonResult(DIMENSIONS, List.of(), "暂无可对比的商品。");
        }
        List<Integer> indexes = resolveIndexes(comparisonTargets, candidates.size());
        if (indexes.isEmpty()) {
            return new ProductComparisonResult(DIMENSIONS, List.of(), "暂无可对比的商品。");
        }
        List<ProductComparisonResult.Row> rows = new ArrayList<>(indexes.size());
        for (Integer index : indexes) {
            ProductSearchCandidate candidate = candidates.get(index - 1);
            rows.add(new ProductComparisonResult.Row(
                    index,
                    candidate.productId(),
                    candidate.externalRef(),
                    candidate.title(),
                    candidate.brand(),
                    candidate.price(),
                    stringAttribute(candidate, "color"),
                    stringAttribute(candidate, "capacity"),
                    candidate.stock(),
                    candidate.matchReasons()
            ));
        }
        return new ProductComparisonResult(DIMENSIONS, rows, summarize(rows));
    }

    private List<Integer> resolveIndexes(List<Integer> requested, int size) {
        Set<Integer> result = new LinkedHashSet<>();
        if (requested != null) {
            for (Integer index : requested) {
                if (index != null && index >= 1 && index <= size) {
                    result.add(index);
                }
            }
        }
        if (result.isEmpty()) {
            int topN = Math.min(size, ragProperties.productQuery().comparison().defaultTopN());
            for (int i = 1; i <= topN; i++) {
                result.add(i);
            }
        }
        return List.copyOf(result);
    }

    private String stringAttribute(ProductSearchCandidate candidate, String key) {
        Object value = candidate.attributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String summarize(List<ProductComparisonResult.Row> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        BigDecimal cheapest = null;
        ProductComparisonResult.Row cheapestRow = null;
        for (ProductComparisonResult.Row row : rows) {
            if (row.price() != null && (cheapest == null || row.price().compareTo(cheapest) < 0)) {
                cheapest = row.price();
                cheapestRow = row;
            }
        }
        if (cheapestRow != null) {
            return "对比的 " + rows.size() + " 件商品中，第 "
                    + cheapestRow.index() + " 件「"
                    + safeTitle(cheapestRow.title()) + "」价格最低，为 ¥"
                    + cheapest.toPlainString() + "。";
        }
        return "对比了 " + rows.size() + " 件商品，请按需选择。";
    }

    private String safeTitle(String title) {
        return title == null ? "未命名商品" : title;
    }
}
