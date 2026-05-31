package com.bytedance.ai.graph.product.retrieval;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品检索的业务硬过滤条件。
 *
 * <p>商品专用 retriever 会把这些条件下推到 SQL WHERE
 * （keyword 路径）或落到 Milvus 命中后的二次校验（semantic 路径），保证返回的 Product
 * 一定不违反这些约束。
 *
 * <p>语义约定：
 * <ul>
 *   <li>区间型字段（{@code priceMin} / {@code priceMax}）为 {@code null} 表示该侧无限制；</li>
 *   <li>白名单型集合（{@code categories} / {@code brands}）为空表示不限制；非空则只接受集合内的值；</li>
 *   <li>黑名单型集合（{@code excludeXxx}）命中即剔除；</li>
 *   <li>{@code mustHaveStock} 为 {@code true} 时只返回 {@code stock > 0} 的 Product。</li>
 * </ul>
 */
public record ProductHardFilter(
        BigDecimal priceMin,
        BigDecimal priceMax,
        List<String> categories,
        List<String> brands,
        List<String> excludeBrands,
        List<String> excludeCategories,
        List<String> excludeColors,
        List<String> excludeMaterials,
        List<String> sizes,
        List<String> materials,
        List<String> includeTerms,
        List<String> excludeTerms,
        boolean mustHaveStock
) {

    public ProductHardFilter {
        categories = copyOrEmpty(categories);
        brands = copyOrEmpty(brands);
        excludeBrands = copyOrEmpty(excludeBrands);
        excludeCategories = copyOrEmpty(excludeCategories);
        excludeColors = copyOrEmpty(excludeColors);
        excludeMaterials = copyOrEmpty(excludeMaterials);
        sizes = copyOrEmpty(sizes);
        materials = copyOrEmpty(materials);
        includeTerms = copyOrEmpty(includeTerms);
        excludeTerms = copyOrEmpty(excludeTerms);
    }

    public static ProductHardFilter empty() {
        return new ProductHardFilter(
                null, null,
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                false
        );
    }

    public boolean isEmpty() {
        return priceMin == null
                && priceMax == null
                && categories.isEmpty()
                && brands.isEmpty()
                && excludeBrands.isEmpty()
                && excludeCategories.isEmpty()
                && excludeColors.isEmpty()
                && excludeMaterials.isEmpty()
                && sizes.isEmpty()
                && materials.isEmpty()
                && includeTerms.isEmpty()
                && excludeTerms.isEmpty()
                && !mustHaveStock;
    }

    private static List<String> copyOrEmpty(List<String> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }
}
