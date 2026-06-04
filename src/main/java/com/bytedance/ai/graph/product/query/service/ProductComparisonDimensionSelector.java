package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
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
 * 对比维度选择器：把用户关注点和候选商品属性合成一个稳定、可解释的维度列表。
 */
@Component
public class ProductComparisonDimensionSelector {

    private static final int MAX_DIMENSIONS = 8;
    private static final Set<String> NOISY_ATTRIBUTE_KEYS = Set.of(
            "id", "productid", "product_id", "spuid", "spu_id",
            "skuid", "sku_id", "image", "imagepath", "image_path",
            "status", "raw", "rawjson", "raw_json", "attributestatus", "attributes_status"
    );

    public List<SelectedDimension> select(
            ProductQueryCondition condition,
            List<ProductSearchCandidate> candidates
    ) {
        LinkedHashMap<String, String> dimensions = new LinkedHashMap<>();
        if (condition != null) {
            addRequestedDimensions(dimensions, condition.requestedDimensions());
            addFocusDimensions(dimensions, condition.compareFocus());
        }

        addIfAbsent(dimensions, "brand", "品牌");
        addIfAbsent(dimensions, "price", "价格");
        addIfAbsent(dimensions, "stock", "库存");
        addCandidateAttributeDimensions(dimensions, candidates);
        addIfAbsent(dimensions, "match_reason", "匹配理由");

        return dimensions.entrySet().stream()
                .limit(MAX_DIMENSIONS)
                .map(entry -> new SelectedDimension(entry.getKey(), entry.getValue()))
                .toList();
    }

    private void addRequestedDimensions(LinkedHashMap<String, String> dimensions, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return;
        }
        for (String raw : requested) {
            DimensionAlias alias = alias(raw);
            if (alias != null) {
                addIfAbsent(dimensions, alias.key(), alias.label());
            }
        }
    }

    private void addFocusDimensions(LinkedHashMap<String, String> dimensions, List<String> focusValues) {
        if (focusValues == null || focusValues.isEmpty()) {
            return;
        }
        for (String raw : focusValues) {
            String focus = normalize(raw);
            if (!StringUtils.hasText(focus)) {
                continue;
            }
            if (containsAny(focus, "性价比", "便宜", "预算", "划算")) {
                addIfAbsent(dimensions, "price", "价格");
                addIfAbsent(dimensions, "rating", "评分/口碑");
                addIfAbsent(dimensions, "features", "核心卖点");
            }
            if (containsAny(focus, "通勤", "出行", "携带", "便携")) {
                addIfAbsent(dimensions, "weight", "重量/便携性");
                addIfAbsent(dimensions, "size", "尺寸");
                addIfAbsent(dimensions, "usage_scenes", "适用场景");
            }
            if (containsAny(focus, "补水", "保湿", "敏感肌", "成分", "护肤")) {
                addIfAbsent(dimensions, "features", "功效/卖点");
                addIfAbsent(dimensions, "ingredients", "成分/材质");
                addIfAbsent(dimensions, "target_audience", "适用人群");
                addIfAbsent(dimensions, "reviews", "用户评价");
            }
            if (containsAny(focus, "正式", "职场", "商务", "面试")) {
                addIfAbsent(dimensions, "material", "材质");
                addIfAbsent(dimensions, "style", "风格");
                addIfAbsent(dimensions, "color", "颜色");
            }
            if (containsAny(focus, "剪辑", "视频", "电脑", "性能", "游戏")) {
                addIfAbsent(dimensions, "cpu", "处理器");
                addIfAbsent(dimensions, "memory", "内存");
                addIfAbsent(dimensions, "storage", "存储");
                addIfAbsent(dimensions, "screen", "屏幕");
                addIfAbsent(dimensions, "battery", "续航");
            }
        }
    }

    private void addCandidateAttributeDimensions(
            LinkedHashMap<String, String> dimensions,
            List<ProductSearchCandidate> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        Map<String, AttributeStats> stats = new LinkedHashMap<>();
        for (ProductSearchCandidate candidate : candidates) {
            if (candidate.attributes().isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Object> entry : candidate.attributes().entrySet()) {
                String key = entry.getKey();
                if (!isUsefulAttributeKey(key) || isBlankValue(entry.getValue())) {
                    continue;
                }
                stats.computeIfAbsent(key, AttributeStats::new).add(entry.getValue());
            }
        }
        stats.values().stream()
                .filter(stat -> stat.coverage() >= Math.min(2, candidates.size()))
                .sorted(Comparator
                        .comparingInt(AttributeStats::distinctCount).reversed()
                        .thenComparing(Comparator.comparingInt(AttributeStats::coverage).reversed()))
                .limit(5)
                .forEach(stat -> addIfAbsent(dimensions, stat.key(), labelForAttribute(stat.key())));
    }

    private DimensionAlias alias(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = normalize(raw);
        if (containsAny(normalized, "价格", "价钱", "售价", "预算", "性价比")) {
            return new DimensionAlias("price", "价格");
        }
        if (containsAny(normalized, "库存", "现货")) {
            return new DimensionAlias("stock", "库存");
        }
        if (containsAny(normalized, "品牌")) {
            return new DimensionAlias("brand", "品牌");
        }
        if (containsAny(normalized, "颜色", "色号")) {
            return new DimensionAlias("color", "颜色");
        }
        if (containsAny(normalized, "容量", "规格")) {
            return new DimensionAlias("capacity", "容量/规格");
        }
        if (containsAny(normalized, "尺寸", "尺码", "大小")) {
            return new DimensionAlias("size", "尺寸");
        }
        if (containsAny(normalized, "材质", "成分")) {
            return new DimensionAlias("material", "材质/成分");
        }
        if (containsAny(normalized, "续航", "电池")) {
            return new DimensionAlias("battery", "续航");
        }
        if (containsAny(normalized, "评价", "口碑", "评论", "评分")) {
            return new DimensionAlias("reviews", "用户评价");
        }
        return new DimensionAlias(raw.trim(), raw.trim());
    }

    private String labelForAttribute(String key) {
        return switch (normalizeKey(key)) {
            case "color" -> "颜色";
            case "capacity" -> "容量/规格";
            case "size" -> "尺寸";
            case "material" -> "材质";
            case "ingredients" -> "成分/材质";
            case "features" -> "核心卖点";
            case "usage_scenes" -> "适用场景";
            case "target_audience" -> "适用人群";
            case "rating" -> "评分/口碑";
            case "weight" -> "重量/便携性";
            case "battery" -> "续航";
            case "memory" -> "内存";
            case "storage" -> "存储";
            case "screen" -> "屏幕";
            case "style" -> "风格";
            case "cpu" -> "处理器";
            default -> key;
        };
    }

    private void addIfAbsent(LinkedHashMap<String, String> dimensions, String key, String label) {
        if (!StringUtils.hasText(key) || dimensions.size() >= MAX_DIMENSIONS) {
            return;
        }
        dimensions.putIfAbsent(key, StringUtils.hasText(label) ? label : key);
    }

    private boolean isUsefulAttributeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        return !NOISY_ATTRIBUTE_KEYS.contains(normalizeKey(key));
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return !StringUtils.hasText(text);
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
    }

    private boolean containsAny(String text, String... values) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeKey(String value) {
        return normalize(value).replace("-", "_").replace(" ", "_");
    }

    public record SelectedDimension(String key, String label) {
    }

    private record DimensionAlias(String key, String label) {
    }

    private static final class AttributeStats {
        private final String key;
        private final Set<String> values = new LinkedHashSet<>();
        private int coverage;

        private AttributeStats(String key) {
            this.key = key;
        }

        private void add(Object value) {
            coverage++;
            values.add(String.valueOf(value));
        }

        private String key() {
            return key;
        }

        private int coverage() {
            return coverage;
        }

        private int distinctCount() {
            return values.size();
        }
    }
}
