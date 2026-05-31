package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import com.bytedance.ai.shared.properties.RagProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * hydrate 之后对 candidate 做精确过滤，弥补 PostgreSQL 粗过滤 / Milvus scalar filter / 向量召回近义匹配
 * 三者的语义不足。
 *
 * <p>规则覆盖：includeTerms / excludeTerms / categoryTerms / excludeCategoryTerms /
 * brandTerms / excludeBrandTerms / price / stock / attributes include & exclude。
 *
 * <p>否定语义白名单：
 * <ul>
 *   <li>"酒精" / "含酒精" 的 excludeTerm 不应误杀 "无酒精/不含酒精/0酒精/零酒精"；</li>
 *   <li>"糖" 不应被误当作 excludeTerm 出现在 "无糖可乐" 这类 includeTerms 上下文里。</li>
 * </ul>
 * 白名单只覆盖 "无/不含/无...的/零/0" 这一类汉语否定前缀，更激进的 NLP 留给后续优化。
 */
@Component
public class ProductCandidatePostFilter {

    private static final Logger log = LoggerFactory.getLogger(ProductCandidatePostFilter.class);

    private static final List<String> NEGATION_PREFIXES = List.of("无", "不含", "零", "0", "免", "非");

    private final RagProperties ragProperties;

    public ProductCandidatePostFilter(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public Result filter(List<ProductSearchCandidate> candidates, ProductQueryCondition condition) {
        if (candidates == null || candidates.isEmpty()) {
            return Result.empty();
        }
        if (condition == null) {
            return new Result(List.copyOf(candidates), List.of(), Map.of());
        }
        List<ProductSearchCandidate> kept = new ArrayList<>();
        List<ProductSearchCandidate> removed = new ArrayList<>();
        Map<String, Integer> reasons = new LinkedHashMap<>();

        boolean mustHaveStock = resolveMustHaveStock(condition);

        for (ProductSearchCandidate candidate : candidates) {
            String drop = evaluate(candidate, condition, mustHaveStock);
            if (drop == null) {
                kept.add(candidate);
            } else {
                removed.add(candidate);
                reasons.merge(drop, 1, Integer::sum);
            }
        }
        return new Result(List.copyOf(kept), List.copyOf(removed), Map.copyOf(reasons));
    }

    private String evaluate(ProductSearchCandidate candidate, ProductQueryCondition condition, boolean mustHaveStock) {
        if (mustHaveStock && (candidate.stock() == null || candidate.stock() <= 0)) {
            return "stock_zero";
        }
        BigDecimal price = candidate.price();
        if (price != null) {
            if (condition.priceMin() != null && price.compareTo(condition.priceMin()) < 0) {
                return "price_below_min";
            }
            if (condition.priceMax() != null && price.compareTo(condition.priceMax()) > 0) {
                return "price_above_max";
            }
        }

        String haystack = candidateHaystack(candidate);

        if (!condition.excludeCategoryTerms().isEmpty()
                && containsAny(candidate.category(), condition.excludeCategoryTerms())) {
            return "category_excluded";
        }
        if (!condition.excludeCategoryTerms().isEmpty()
                && containsAny(candidate.subCategory(), condition.excludeCategoryTerms())) {
            return "sub_category_excluded";
        }
        if (!condition.categoryTerms().isEmpty()
                && !containsAny(candidate.category(), condition.categoryTerms())
                && !containsAny(candidate.subCategory(), condition.categoryTerms())
                && !containsAny(haystack, condition.categoryTerms())) {
            return "category_missing";
        }
        if (!condition.excludeBrandTerms().isEmpty()
                && containsAny(candidate.brand(), condition.excludeBrandTerms())) {
            return "brand_excluded";
        }
        if (!condition.brandTerms().isEmpty()
                && !containsAny(candidate.brand(), condition.brandTerms())) {
            return "brand_missing";
        }

        for (String excludeTerm : condition.excludeTerms()) {
            if (matchesAsExcluded(haystack, excludeTerm)) {
                return "exclude_term_hit:" + excludeTerm;
            }
        }

        ProductAttributesCondition attributes = condition.attributes();
        if (attributes != null) {
            String attrDrop = evaluateAttributes(candidate, attributes);
            if (attrDrop != null) {
                return attrDrop;
            }
        }

        // includeTerms：candidate 至少命中其中一个；空则不限制
        if (!condition.includeTerms().isEmpty()
                && !containsAny(haystack, condition.includeTerms())) {
            return "include_terms_missing";
        }

        return null;
    }

    private String evaluateAttributes(ProductSearchCandidate candidate, ProductAttributesCondition attributes) {
        Map<String, Object> attrMap = candidate.attributes();
        if (!attributes.color().exclude().isEmpty()
                && attributeContainsAny(attrMap, "color", attributes.color().exclude())) {
            return "attr_color_excluded";
        }
        if (!attributes.color().include().isEmpty()
                && !attributeContainsAny(attrMap, "color", attributes.color().include())) {
            return "attr_color_missing";
        }
        if (!attributes.size().exclude().isEmpty()
                && attributeContainsAny(attrMap, "size", attributes.size().exclude())) {
            return "attr_size_excluded";
        }
        if (!attributes.size().include().isEmpty()
                && !attributeContainsAny(attrMap, "size", attributes.size().include())) {
            return "attr_size_missing";
        }
        if (!attributes.material().exclude().isEmpty()
                && attributeContainsAny(attrMap, "material", attributes.material().exclude())) {
            return "attr_material_excluded";
        }
        if (!attributes.material().include().isEmpty()
                && !attributeContainsAny(attrMap, "material", attributes.material().include())) {
            return "attr_material_missing";
        }
        return null;
    }

    private boolean attributeContainsAny(Map<String, Object> attributes, String key, List<String> values) {
        Object raw = attributes == null ? null : attributes.get(key);
        if (raw == null) {
            return false;
        }
        if (raw instanceof CharSequence cs) {
            return containsAny(cs.toString(), values);
        }
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && containsAny(item.toString(), values)) {
                    return true;
                }
            }
            return false;
        }
        return containsAny(raw.toString(), values);
    }

    private String candidateHaystack(ProductSearchCandidate candidate) {
        StringBuilder builder = new StringBuilder();
        if (candidate.title() != null) {
            builder.append(candidate.title()).append(' ');
        }
        if (candidate.brand() != null) {
            builder.append(candidate.brand()).append(' ');
        }
        if (candidate.category() != null) {
            builder.append(candidate.category()).append(' ');
        }
        if (candidate.subCategory() != null) {
            builder.append(candidate.subCategory()).append(' ');
        }
        for (Map.Entry<String, Object> entry : candidate.attributes().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item != null) {
                        builder.append(item).append(' ');
                    }
                }
            } else if (value != null) {
                builder.append(value).append(' ');
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private boolean matchesAsExcluded(String haystack, String excludeTerm) {
        if (excludeTerm == null || excludeTerm.isBlank()) {
            return false;
        }
        String needle = excludeTerm.trim().toLowerCase(Locale.ROOT);
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int hit = haystack.indexOf(needle, from);
            if (hit < 0) {
                return false;
            }
            if (!isNegatedAt(haystack, hit)) {
                return true;
            }
            from = hit + needle.length();
        }
        return false;
    }

    private boolean isNegatedAt(String haystack, int hit) {
        if (hit <= 0) {
            return false;
        }
        for (String prefix : NEGATION_PREFIXES) {
            int prefixStart = hit - prefix.length();
            if (prefixStart < 0) {
                continue;
            }
            if (haystack.regionMatches(prefixStart, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, List<String> terms) {
        if (text == null || text.isEmpty() || terms == null || terms.isEmpty()) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            if (lowered.contains(term.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean resolveMustHaveStock(ProductQueryCondition condition) {
        Boolean hint = condition.mustHaveStock();
        if (hint != null) {
            return hint;
        }
        return ragProperties.productQuery().mustHaveStockDefault();
    }

    /**
     * post-filter 结果。
     *
     * @param kept           保留的候选
     * @param removed        被剔除的候选
     * @param removalReasons reason → 命中次数
     */
    public record Result(
            List<ProductSearchCandidate> kept,
            List<ProductSearchCandidate> removed,
            Map<String, Integer> removalReasons
    ) {
        public static Result empty() {
            return new Result(List.of(), List.of(), Map.of());
        }
    }
}
