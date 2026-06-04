package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.ProductComparisonResult;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import com.bytedance.ai.shared.properties.RagProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
    private final ProductComparisonDimensionSelector dimensionSelector;

    public ProductComparisonBuilder(
            RagProperties ragProperties,
            ProductComparisonDimensionSelector dimensionSelector
    ) {
        this.ragProperties = ragProperties;
        this.dimensionSelector = dimensionSelector;
    }

    public ProductComparisonResult build(
            List<ProductSearchCandidate> candidates,
            List<Integer> comparisonTargets
    ) {
        return build(candidates, comparisonTargets, null);
    }

    public ProductComparisonResult build(
            List<ProductSearchCandidate> candidates,
            List<Integer> comparisonTargets,
            ProductQueryCondition condition
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return new ProductComparisonResult(DIMENSIONS, List.of(), "暂无可对比的商品。");
        }
        List<Integer> indexes = resolveIndexes(
                comparisonTargets,
                condition == null ? List.of() : condition.comparisonTargetTexts(),
                candidates
        );
        if (indexes.isEmpty()) {
            return new ProductComparisonResult(DIMENSIONS, List.of(), "暂无可对比的商品。");
        }
        List<ProductComparisonResult.Row> rows = new ArrayList<>(indexes.size());
        List<ProductComparisonResult.ProductColumn> products = new ArrayList<>(indexes.size());
        List<IndexedCandidate> selected = new ArrayList<>(indexes.size());
        for (Integer index : indexes) {
            ProductSearchCandidate candidate = candidates.get(index - 1);
            selected.add(new IndexedCandidate(index, candidate));
            products.add(new ProductComparisonResult.ProductColumn(
                    index,
                    candidate.productId(),
                    candidate.externalRef(),
                    candidate.title(),
                    candidate.brand(),
                    candidate.price()
            ));
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
        List<ProductComparisonDimensionSelector.SelectedDimension> dimensions =
                dimensionSelector.select(condition, selected.stream().map(IndexedCandidate::candidate).toList());
        List<ProductComparisonResult.DimensionRow> dimensionRows = buildDimensionRows(dimensions, selected);
        ProductComparisonResult.Decision decision = buildDecision(selected, dimensionRows, condition);
        List<String> caveats = buildCaveats(dimensionRows);
        String summary = summarize(rows, decision);
        return new ProductComparisonResult(
                dimensionRows.stream().map(ProductComparisonResult.DimensionRow::key).toList(),
                rows,
                summary,
                products,
                dimensionRows,
                decision,
                caveats
        );
    }

    private List<Integer> resolveIndexes(
            List<Integer> requested,
            List<String> targetTexts,
            List<ProductSearchCandidate> candidates
    ) {
        int size = candidates.size();
        Set<Integer> result = new LinkedHashSet<>();
        if (requested != null) {
            for (Integer index : requested) {
                if (index != null && index >= 1 && index <= size && result.size() < 3) {
                    result.add(index);
                }
            }
        }
        if (result.isEmpty() && targetTexts != null && !targetTexts.isEmpty()) {
            result.addAll(resolveTextTargets(targetTexts, candidates));
        }
        if (result.isEmpty()) {
            int topN = Math.min(size, Math.min(3, ragProperties.productQuery().comparison().defaultTopN()));
            for (int i = 1; i <= topN; i++) {
                result.add(i);
            }
        }
        return List.copyOf(result);
    }

    private List<Integer> resolveTextTargets(List<String> targetTexts, List<ProductSearchCandidate> candidates) {
        Set<Integer> result = new LinkedHashSet<>();
        Set<Integer> used = new LinkedHashSet<>();
        for (String text : targetTexts) {
            if (!StringUtils.hasText(text) || result.size() >= 3) {
                continue;
            }
            int matched = bestTextMatch(text, candidates, used);
            if (matched > 0) {
                result.add(matched);
                used.add(matched);
            }
        }
        return result.size() >= 2 ? List.copyOf(result) : List.of();
    }

    private int bestTextMatch(String text, List<ProductSearchCandidate> candidates, Set<Integer> used) {
        String needle = normalize(text);
        if (!StringUtils.hasText(needle)) {
            return -1;
        }
        int bestIndex = -1;
        int bestScore = 0;
        for (int i = 0; i < candidates.size(); i++) {
            int index = i + 1;
            if (used.contains(index)) {
                continue;
            }
            ProductSearchCandidate candidate = candidates.get(i);
            String haystack = normalize(String.join(" ",
                    safe(candidate.title()),
                    safe(candidate.brand()),
                    safe(candidate.externalRef()),
                    String.valueOf(candidate.attributes())
            ));
            int score = matchScore(needle, haystack);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestScore >= 1 ? bestIndex : -1;
    }

    private int matchScore(String needle, String haystack) {
        if (!StringUtils.hasText(needle) || !StringUtils.hasText(haystack)) {
            return 0;
        }
        if (haystack.contains(needle)) {
            return 3;
        }
        int score = 0;
        for (String token : needle.split("\\s+")) {
            if (token.length() >= 2 && haystack.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private String stringAttribute(ProductSearchCandidate candidate, String key) {
        Object value = candidate.attributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private List<ProductComparisonResult.DimensionRow> buildDimensionRows(
            List<ProductComparisonDimensionSelector.SelectedDimension> dimensions,
            List<IndexedCandidate> selected
    ) {
        List<ProductComparisonResult.DimensionRow> rows = new ArrayList<>();
        for (ProductComparisonDimensionSelector.SelectedDimension dimension : dimensions) {
            List<ProductComparisonResult.Cell> cells = new ArrayList<>(selected.size());
            Map<Integer, ComparableValue> comparableValues = new LinkedHashMap<>();
            for (IndexedCandidate item : selected) {
                CellValue value = valueFor(dimension.key(), item.candidate());
                cells.add(new ProductComparisonResult.Cell(
                        item.index(),
                        value.display(),
                        value.missing(),
                        value.evidence()
                ));
                if (value.comparable() != null) {
                    comparableValues.put(item.index(), value.comparable());
                }
            }
            Winner winner = winnerFor(dimension.key(), comparableValues);
            rows.add(new ProductComparisonResult.DimensionRow(
                    dimension.key(),
                    dimension.label(),
                    cells,
                    winner == null ? null : winner.index(),
                    winner == null ? "" : winner.reason()
            ));
        }
        return List.copyOf(rows);
    }

    private CellValue valueFor(String key, ProductSearchCandidate candidate) {
        return switch (normalizeKey(key)) {
            case "title" -> textValue(candidate.title());
            case "brand" -> textValue(candidate.brand());
            case "category" -> textValue(joinNonBlank(candidate.category(), candidate.subCategory()));
            case "price" -> candidate.price() == null
                    ? missingValue()
                    : new CellValue(
                            "¥" + candidate.price().toPlainString(),
                            false,
                            List.of(),
                            new ComparableValue(candidate.price())
                    );
            case "stock" -> candidate.stock() == null
                    ? missingValue()
                    : new CellValue(
                            String.valueOf(candidate.stock()),
                            false,
                            List.of(),
                            new ComparableValue(BigDecimal.valueOf(candidate.stock()))
                    );
            case "match_reason" -> candidate.matchReasons().isEmpty()
                    ? missingValue()
                    : new CellValue(String.join("、", candidate.matchReasons()), false, List.of(), null);
            case "reviews" -> reviewValue(candidate);
            default -> attributeValue(candidate, key);
        };
    }

    private CellValue attributeValue(ProductSearchCandidate candidate, String key) {
        Object value = lookupAttribute(candidate.attributes(), key);
        if (value == null) {
            return missingValue();
        }
        String display = displayValue(value);
        if (!StringUtils.hasText(display)) {
            return missingValue();
        }
        ComparableValue comparable = numericComparable(value);
        return new CellValue(display, false, List.of("attributes." + key), comparable);
    }

    private Object lookupAttribute(Map<String, Object> attributes, String key) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        Object direct = attributes.get(key);
        if (direct != null) {
            return direct;
        }
        String normalizedKey = normalizeKey(key);
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (normalizeKey(entry.getKey()).equals(normalizedKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private CellValue reviewValue(ProductSearchCandidate candidate) {
        if (candidate.reviews().isEmpty()) {
            Object rating = lookupAttribute(candidate.attributes(), "rating");
            return rating == null ? missingValue() : attributeValue(candidate, "rating");
        }
        String reviewSummary = candidate.reviews().stream()
                .limit(2)
                .map(review -> {
                    String prefix = review.rating() == null ? "" : review.rating() + "星 ";
                    return prefix + safeReview(review.content());
                })
                .filter(StringUtils::hasText)
                .toList()
                .stream()
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
        return StringUtils.hasText(reviewSummary)
                ? new CellValue(reviewSummary, false, List.of("reviews"), null)
                : missingValue();
    }

    private Winner winnerFor(String key, Map<Integer, ComparableValue> values) {
        if (values.size() < 2) {
            return null;
        }
        String normalizedKey = normalizeKey(key);
        Comparator<Map.Entry<Integer, ComparableValue>> comparator =
                Comparator.comparing(entry -> entry.getValue().number());
        Map.Entry<Integer, ComparableValue> winner;
        String reason;
        if ("price".equals(normalizedKey)) {
            winner = values.entrySet().stream().min(comparator).orElse(null);
            reason = "价格最低";
        } else if ("stock".equals(normalizedKey)) {
            winner = values.entrySet().stream().max(comparator).orElse(null);
            reason = "库存最多";
        } else if ("rating".equals(normalizedKey)) {
            winner = values.entrySet().stream().max(comparator).orElse(null);
            reason = "评分更高";
        } else {
            return null;
        }
        return winner == null ? null : new Winner(winner.getKey(), reason);
    }

    private ProductComparisonResult.Decision buildDecision(
            List<IndexedCandidate> selected,
            List<ProductComparisonResult.DimensionRow> dimensionRows,
            ProductQueryCondition condition
    ) {
        if (selected.isEmpty()) {
            return ProductComparisonResult.Decision.empty();
        }
        IndexedCandidate recommended = selected.stream()
                .max(Comparator.comparingDouble(item -> item.candidate().scoreFinal()))
                .orElse(selected.get(0));
        List<String> reasons = new ArrayList<>();
        reasons.add("综合匹配度最高");
        ProductComparisonResult.DimensionRow priceWinner = rowWinner(dimensionRows, "price");
        if (priceWinner != null && recommended.index() == priceWinner.winnerIndex()) {
            reasons.add("价格最低");
        }
        ProductComparisonResult.DimensionRow stockWinner = rowWinner(dimensionRows, "stock");
        if (stockWinner != null && recommended.index() == stockWinner.winnerIndex()) {
            reasons.add("库存相对更充足");
        }
        String focus = condition == null || condition.compareFocus().isEmpty()
                ? ""
                : "，更贴近「" + String.join("、", condition.compareFocus()) + "」";
        String recommendation = "推荐第 " + recommended.index() + " 件「"
                + safeTitle(recommended.candidate().title()) + "」" + focus + "。";
        List<String> tradeoffs = selected.stream()
                .filter(item -> item.index() != recommended.index())
                .limit(2)
                .map(item -> "第 " + item.index() + " 件可作为备选，主要取舍看表格中的价格、库存和属性差异。")
                .toList();
        return new ProductComparisonResult.Decision(
                recommended.index(),
                recommendation,
                reasons,
                tradeoffs
        );
    }

    private ProductComparisonResult.DimensionRow rowWinner(
            List<ProductComparisonResult.DimensionRow> rows,
            String key
    ) {
        for (ProductComparisonResult.DimensionRow row : rows) {
            if (normalizeKey(row.key()).equals(key) && row.winnerIndex() != null) {
                return row;
            }
        }
        return null;
    }

    private List<String> buildCaveats(List<ProductComparisonResult.DimensionRow> dimensionRows) {
        List<String> caveats = new ArrayList<>();
        for (ProductComparisonResult.DimensionRow row : dimensionRows) {
            long missingCount = row.cells().stream().filter(ProductComparisonResult.Cell::missing).count();
            if (missingCount > 0 && missingCount < row.cells().size()) {
                caveats.add(row.label() + " 有部分商品暂无数据");
            } else if (missingCount == row.cells().size()) {
                caveats.add(row.label() + " 暂无可用数据");
            }
        }
        return caveats.stream().distinct().limit(3).toList();
    }

    private String summarize(
            List<ProductComparisonResult.Row> rows,
            ProductComparisonResult.Decision decision
    ) {
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
        String cheapestSummary = "";
        if (cheapestRow != null) {
            cheapestSummary = "对比的 " + rows.size() + " 件商品中，第 "
                    + cheapestRow.index() + " 件「"
                    + safeTitle(cheapestRow.title()) + "」价格最低，为 ¥"
                    + cheapest.toPlainString() + "。";
        }
        if (decision != null && StringUtils.hasText(decision.recommendation())) {
            return StringUtils.hasText(cheapestSummary)
                    ? decision.recommendation() + cheapestSummary
                    : decision.recommendation();
        }
        if (StringUtils.hasText(cheapestSummary)) {
            return cheapestSummary;
        }
        return "对比了 " + rows.size() + " 件商品，请按需选择。";
    }

    private CellValue textValue(String value) {
        return StringUtils.hasText(value)
                ? new CellValue(value, false, List.of(), null)
                : missingValue();
    }

    private CellValue missingValue() {
        return new CellValue("暂无数据", true, List.of(), null);
    }

    private ComparableValue numericComparable(Object value) {
        if (value instanceof BigDecimal decimal) {
            return new ComparableValue(decimal);
        }
        if (value instanceof Number number) {
            return new ComparableValue(BigDecimal.valueOf(number.doubleValue()));
        }
        try {
            return new ComparableValue(new BigDecimal(String.valueOf(value).replaceAll("[^0-9.\\-]", "")));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String displayValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(StringUtils::hasText)
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("");
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .limit(4)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("");
        }
        return String.valueOf(value);
    }

    private String safeReview(String content) {
        if (!StringUtils.hasText(content)) {
            return "未填写评价内容";
        }
        String normalized = content.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 36 ? normalized : normalized.substring(0, 36) + "...";
    }

    private String joinNonBlank(String left, String right) {
        if (StringUtils.hasText(left) && StringUtils.hasText(right)) {
            return left + "/" + right;
        }
        return StringUtils.hasText(left) ? left : right;
    }

    private String safeTitle(String title) {
        return title == null ? "未命名商品" : title;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeKey(String value) {
        return normalize(value).replace("-", "_").replace(" ", "_");
    }

    private record IndexedCandidate(int index, ProductSearchCandidate candidate) {
    }

    private record CellValue(
            String display,
            boolean missing,
            List<String> evidence,
            ComparableValue comparable
    ) {
    }

    private record ComparableValue(BigDecimal number) {
    }

    private record Winner(int index, String reason) {
    }
}
