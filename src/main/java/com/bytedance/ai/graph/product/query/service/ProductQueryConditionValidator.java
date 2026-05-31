package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.AttributeIncludeExclude;
import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 纯 Java validator：净化 LLM 解析后的 {@link ProductQueryCondition}。
 * 不调用任何外部依赖；商业 ID 净化交给 {@code ProductQueryConditionSanitizer}。
 */
@Component
public class ProductQueryConditionValidator {

    private static final double CLARIFY_CONFIDENCE_THRESHOLD = 0.5d;

    public ProductQueryCondition validate(ProductQueryCondition condition) {
        if (condition == null) {
            return ProductQueryCondition.empty("");
        }

        List<String> missingSlots = new ArrayList<>(condition.missingSlots());

        BigDecimal priceMin = condition.priceMin();
        BigDecimal priceMax = condition.priceMax();
        if (priceMin != null && priceMin.compareTo(BigDecimal.ZERO) < 0) {
            priceMin = null;
            addUnique(missingSlots, "price");
        }
        if (priceMax != null && priceMax.compareTo(BigDecimal.ZERO) < 0) {
            priceMax = null;
            addUnique(missingSlots, "price");
        }
        if (priceMin != null && priceMax != null && priceMin.compareTo(priceMax) > 0) {
            BigDecimal swap = priceMin;
            priceMin = priceMax;
            priceMax = swap;
        }

        List<String> excludeTerms = dedupe(condition.excludeTerms());
        List<String> includeTerms = dedupeExcluding(condition.includeTerms(), excludeTerms);

        List<String> excludeCategoryTerms = dedupe(condition.excludeCategoryTerms());
        List<String> categoryTerms = dedupeExcluding(condition.categoryTerms(), excludeCategoryTerms);
        List<String> excludeBrandTerms = dedupe(condition.excludeBrandTerms());
        List<String> brandTerms = dedupeExcluding(condition.brandTerms(), excludeBrandTerms);

        ProductAttributesCondition attributes = sanitizeAttributes(condition.attributes());

        double confidence = clamp(condition.confidence(), 0.0d, 1.0d);
        boolean needClarify = condition.needClarify() || confidence < CLARIFY_CONFIDENCE_THRESHOLD;

        return new ProductQueryCondition(
                condition.rawQuery(),
                condition.normalizedQuery(),
                defaultIfBlank(condition.intent(), "QUERY"),
                defaultIfBlank(condition.queryMode(), "HYBRID"),
                condition.keywordQuery(),
                condition.semanticQuery(),
                categoryTerms,
                excludeCategoryTerms,
                brandTerms,
                excludeBrandTerms,
                includeTerms,
                excludeTerms,
                attributes,
                priceMin,
                priceMax,
                condition.mustHaveStock(),
                defaultIfBlank(condition.sort(), "RELEVANCE"),
                defaultIfBlank(condition.refineType(), "RESET"),
                condition.comparisonTargets(),
                condition.needComparison(),
                confidence,
                needClarify,
                List.copyOf(missingSlots)
        );
    }

    private ProductAttributesCondition sanitizeAttributes(ProductAttributesCondition attributes) {
        ProductAttributesCondition source = attributes == null ? ProductAttributesCondition.empty() : attributes;
        return new ProductAttributesCondition(
                sanitizeIncludeExclude(source.color()),
                sanitizeIncludeExclude(source.size()),
                sanitizeIncludeExclude(source.material()),
                blankToNull(source.capacity())
        );
    }

    private AttributeIncludeExclude sanitizeIncludeExclude(AttributeIncludeExclude value) {
        AttributeIncludeExclude source = value == null ? AttributeIncludeExclude.empty() : value;
        List<String> exclude = dedupe(source.exclude());
        List<String> include = dedupeExcluding(source.include(), exclude);
        return new AttributeIncludeExclude(include, exclude);
    }

    private List<String> dedupe(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                seen.add(value.trim());
            }
        }
        return List.copyOf(seen);
    }

    private List<String> dedupeExcluding(List<String> values, List<String> excludes) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> excludedLower = new LinkedHashSet<>();
        for (String exclude : excludes) {
            excludedLower.add(exclude.toLowerCase());
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (!excludedLower.contains(trimmed.toLowerCase())) {
                seen.add(trimmed);
            }
        }
        return List.copyOf(seen);
    }

    private void addUnique(List<String> bucket, String value) {
        if (!bucket.contains(value)) {
            bucket.add(value);
        }
    }

    private double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
