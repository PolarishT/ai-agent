package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.AttributeIncludeExclude;
import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 多轮 refine 合并：根据当前 condition 的 {@code refineType} 决定如何与上一轮 condition 合并。
 *
 * <ul>
 *   <li>{@code INHERIT}（"再便宜点"）：继承上一轮全部字段；若当前轮没显式收紧 priceMax，
 *       按上一轮 0.8 倍保底收紧。</li>
 *   <li>{@code OVERRIDE}（"就要黑色"）：用当前轮值覆盖对应字段，并把对应 token 从 exclude 中移除。</li>
 *   <li>{@code APPEND}（"不要黑色"）：在上一轮基础上追加当前轮的 excludeTerms / 属性 exclude。</li>
 *   <li>{@code RESET}（"重新搜"）：丢弃上一轮，纯用当前 condition。</li>
 * </ul>
 */
@Component
public class ProductQueryConditionMerger {

    private static final BigDecimal INHERIT_PRICE_TIGHTENING_FACTOR = new BigDecimal("0.8");

    public ProductQueryCondition merge(ProductQueryCondition current, ProductQueryCondition previous) {
        if (current == null) {
            return previous == null ? null : previous;
        }
        String refineType = current.refineType() == null ? "RESET" : current.refineType();
        if (previous == null || "RESET".equals(refineType)) {
            return current;
        }
        return switch (refineType) {
            case "INHERIT" -> inheritFromPrevious(current, previous);
            case "OVERRIDE" -> overrideFromPrevious(current, previous);
            case "APPEND" -> appendOntoPrevious(current, previous);
            default -> current;
        };
    }

    private ProductQueryCondition inheritFromPrevious(ProductQueryCondition current, ProductQueryCondition previous) {
        BigDecimal priceMax = current.priceMax();
        if (priceMax == null && previous.priceMax() != null) {
            priceMax = previous.priceMax()
                    .multiply(INHERIT_PRICE_TIGHTENING_FACTOR)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal priceMin = current.priceMin() == null ? previous.priceMin() : current.priceMin();
        return new ProductQueryCondition(
                current.rawQuery(),
                current.normalizedQuery(),
                current.intent(),
                pickPreferringCurrent(current.queryMode(), previous.queryMode()),
                pickPreferringCurrent(current.keywordQuery(), previous.keywordQuery()),
                pickPreferringCurrent(current.semanticQuery(), previous.semanticQuery()),
                pickPreferringPrevious(current.categoryTerms(), previous.categoryTerms()),
                pickPreferringPrevious(current.excludeCategoryTerms(), previous.excludeCategoryTerms()),
                pickPreferringPrevious(current.brandTerms(), previous.brandTerms()),
                pickPreferringPrevious(current.excludeBrandTerms(), previous.excludeBrandTerms()),
                pickPreferringPrevious(current.includeTerms(), previous.includeTerms()),
                pickPreferringPrevious(current.excludeTerms(), previous.excludeTerms()),
                inheritAttributes(current.attributes(), previous.attributes()),
                priceMin,
                priceMax,
                current.mustHaveStock() == null ? previous.mustHaveStock() : current.mustHaveStock(),
                pickPreferringCurrent(current.sort(), previous.sort()),
                current.refineType(),
                current.comparisonTargets(),
                current.needComparison(),
                current.confidence(),
                current.needClarify(),
                current.missingSlots()
        );
    }

    private ProductQueryCondition overrideFromPrevious(ProductQueryCondition current, ProductQueryCondition previous) {
        ProductAttributesCondition mergedAttributes = overrideAttributes(current.attributes(), previous.attributes());
        List<String> excludeTerms = removeOverriddenTokens(previous.excludeTerms(), current.includeTerms());
        excludeTerms = appendUnique(excludeTerms, current.excludeTerms());
        List<String> excludeCategoryTerms = removeOverriddenTokens(previous.excludeCategoryTerms(), current.categoryTerms());
        excludeCategoryTerms = appendUnique(excludeCategoryTerms, current.excludeCategoryTerms());
        List<String> excludeBrandTerms = removeOverriddenTokens(previous.excludeBrandTerms(), current.brandTerms());
        excludeBrandTerms = appendUnique(excludeBrandTerms, current.excludeBrandTerms());
        return new ProductQueryCondition(
                current.rawQuery(),
                current.normalizedQuery(),
                current.intent(),
                pickPreferringCurrent(current.queryMode(), previous.queryMode()),
                pickPreferringCurrent(current.keywordQuery(), previous.keywordQuery()),
                pickPreferringCurrent(current.semanticQuery(), previous.semanticQuery()),
                pickPreferringCurrent(current.categoryTerms(), previous.categoryTerms()),
                excludeCategoryTerms,
                pickPreferringCurrent(current.brandTerms(), previous.brandTerms()),
                excludeBrandTerms,
                pickPreferringCurrent(current.includeTerms(), previous.includeTerms()),
                excludeTerms,
                mergedAttributes,
                current.priceMin() == null ? previous.priceMin() : current.priceMin(),
                current.priceMax() == null ? previous.priceMax() : current.priceMax(),
                current.mustHaveStock() == null ? previous.mustHaveStock() : current.mustHaveStock(),
                pickPreferringCurrent(current.sort(), previous.sort()),
                current.refineType(),
                current.comparisonTargets(),
                current.needComparison(),
                current.confidence(),
                current.needClarify(),
                current.missingSlots()
        );
    }

    private ProductQueryCondition appendOntoPrevious(ProductQueryCondition current, ProductQueryCondition previous) {
        List<String> excludeTerms = appendUnique(previous.excludeTerms(), current.excludeTerms());
        List<String> includeTerms = appendUnique(previous.includeTerms(), current.includeTerms());
        List<String> excludeCategoryTerms = appendUnique(previous.excludeCategoryTerms(), current.excludeCategoryTerms());
        List<String> excludeBrandTerms = appendUnique(previous.excludeBrandTerms(), current.excludeBrandTerms());
        ProductAttributesCondition mergedAttributes = appendAttributes(current.attributes(), previous.attributes());
        return new ProductQueryCondition(
                current.rawQuery(),
                current.normalizedQuery(),
                current.intent(),
                pickPreferringCurrent(current.queryMode(), previous.queryMode()),
                pickPreferringCurrent(current.keywordQuery(), previous.keywordQuery()),
                pickPreferringCurrent(current.semanticQuery(), previous.semanticQuery()),
                pickPreferringPrevious(current.categoryTerms(), previous.categoryTerms()),
                excludeCategoryTerms,
                pickPreferringPrevious(current.brandTerms(), previous.brandTerms()),
                excludeBrandTerms,
                includeTerms,
                excludeTerms,
                mergedAttributes,
                current.priceMin() == null ? previous.priceMin() : current.priceMin(),
                current.priceMax() == null ? previous.priceMax() : current.priceMax(),
                current.mustHaveStock() == null ? previous.mustHaveStock() : current.mustHaveStock(),
                pickPreferringCurrent(current.sort(), previous.sort()),
                current.refineType(),
                current.comparisonTargets(),
                current.needComparison(),
                current.confidence(),
                current.needClarify(),
                current.missingSlots()
        );
    }

    private ProductAttributesCondition inheritAttributes(
            ProductAttributesCondition current,
            ProductAttributesCondition previous
    ) {
        return new ProductAttributesCondition(
                inheritIncludeExclude(current.color(), previous.color()),
                inheritIncludeExclude(current.size(), previous.size()),
                inheritIncludeExclude(current.material(), previous.material()),
                current.capacity() == null ? previous.capacity() : current.capacity()
        );
    }

    private AttributeIncludeExclude inheritIncludeExclude(
            AttributeIncludeExclude current,
            AttributeIncludeExclude previous
    ) {
        if (current.isEmpty()) {
            return previous;
        }
        return new AttributeIncludeExclude(
                appendUnique(previous.include(), current.include()),
                appendUnique(previous.exclude(), current.exclude())
        );
    }

    private ProductAttributesCondition overrideAttributes(
            ProductAttributesCondition current,
            ProductAttributesCondition previous
    ) {
        return new ProductAttributesCondition(
                overrideIncludeExclude(current.color(), previous.color()),
                overrideIncludeExclude(current.size(), previous.size()),
                overrideIncludeExclude(current.material(), previous.material()),
                current.capacity() == null ? previous.capacity() : current.capacity()
        );
    }

    private AttributeIncludeExclude overrideIncludeExclude(
            AttributeIncludeExclude current,
            AttributeIncludeExclude previous
    ) {
        if (current.isEmpty()) {
            return previous;
        }
        // OVERRIDE 含义：用户明确表达「就要 X」时，把 X 从 previous.exclude 中拿掉、放进 include。
        List<String> exclude = removeOverriddenTokens(previous.exclude(), current.include());
        return new AttributeIncludeExclude(current.include(), exclude);
    }

    private ProductAttributesCondition appendAttributes(
            ProductAttributesCondition current,
            ProductAttributesCondition previous
    ) {
        return new ProductAttributesCondition(
                appendIncludeExclude(current.color(), previous.color()),
                appendIncludeExclude(current.size(), previous.size()),
                appendIncludeExclude(current.material(), previous.material()),
                current.capacity() == null ? previous.capacity() : current.capacity()
        );
    }

    private AttributeIncludeExclude appendIncludeExclude(
            AttributeIncludeExclude current,
            AttributeIncludeExclude previous
    ) {
        return new AttributeIncludeExclude(
                appendUnique(previous.include(), current.include()),
                appendUnique(previous.exclude(), current.exclude())
        );
    }

    private List<String> removeOverriddenTokens(List<String> excludes, List<String> includes) {
        if (excludes == null || excludes.isEmpty()) {
            return List.of();
        }
        if (includes == null || includes.isEmpty()) {
            return List.copyOf(excludes);
        }
        Set<String> includeLower = new LinkedHashSet<>();
        for (String include : includes) {
            includeLower.add(include.toLowerCase());
        }
        List<String> filtered = new ArrayList<>();
        for (String exclude : excludes) {
            if (!includeLower.contains(exclude.toLowerCase())) {
                filtered.add(exclude);
            }
        }
        return List.copyOf(filtered);
    }

    private List<String> appendUnique(List<String> base, List<String> extra) {
        if ((base == null || base.isEmpty()) && (extra == null || extra.isEmpty())) {
            return List.of();
        }
        Set<String> merged = new LinkedHashSet<>();
        if (base != null) {
            merged.addAll(base);
        }
        if (extra != null) {
            merged.addAll(extra);
        }
        return List.copyOf(merged);
    }

    private String pickPreferringCurrent(String current, String previous) {
        if (current != null && !current.isBlank()) {
            return current;
        }
        return previous;
    }

    private <T> List<T> pickPreferringCurrent(List<T> current, List<T> previous) {
        if (current != null && !current.isEmpty()) {
            return current;
        }
        return previous == null ? List.of() : List.copyOf(previous);
    }

    private <T> List<T> pickPreferringPrevious(List<T> current, List<T> previous) {
        if (previous != null && !previous.isEmpty()) {
            return previous;
        }
        return current == null ? List.of() : List.copyOf(current);
    }
}
