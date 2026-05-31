package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.AttributeIncludeExclude;
import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.ProductHardFilter;
import com.bytedance.ai.shared.properties.RagProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 把 {@link ProductQueryCondition} 翻译为下推到 SQL 的 {@link ProductHardFilter}。
 *
 * <p>规则：
 * <ul>
 *   <li>价格 / 品牌 / 类目 / 排除词全部直通；</li>
 *   <li>属性 {@code color.exclude} → {@code excludeColors}；{@code color.include}
 *       同时也并入顶层 {@code includeTerms}（让基础 ranker 拿到加权依据）；</li>
 *   <li>{@code material} 同上；</li>
 *   <li>{@code size.include} 直通 {@code sizes}；</li>
 *   <li>{@code mustHaveStock} 取 condition 提示，无提示时从配置 {@code mustHaveStockDefault} 兜底。</li>
 * </ul>
 */
@Component
public class ProductSearchFilterBuilder {

    private final RagProperties ragProperties;

    public ProductSearchFilterBuilder(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public ProductHardFilter build(ProductQueryCondition condition) {
        if (condition == null) {
            return ProductHardFilter.empty();
        }
        ProductAttributesCondition attributes = condition.attributes() == null
                ? ProductAttributesCondition.empty()
                : condition.attributes();

        Set<String> includeTerms = new LinkedHashSet<>(condition.includeTerms());
        appendAll(includeTerms, attributes.color().include());
        appendAll(includeTerms, attributes.material().include());
        // capacity 是单值字符串，作为 include term 让 ranker 加权
        if (attributes.capacity() != null && !attributes.capacity().isBlank()) {
            includeTerms.add(attributes.capacity());
        }

        Boolean mustHaveStockHint = condition.mustHaveStock();
        boolean mustHaveStock = mustHaveStockHint != null
                ? mustHaveStockHint
                : ragProperties.productQuery().mustHaveStockDefault();

        return new ProductHardFilter(
                condition.priceMin(),
                condition.priceMax(),
                condition.categoryTerms(),
                condition.brandTerms(),
                condition.excludeBrandTerms(),
                condition.excludeCategoryTerms(),
                attributes.color().exclude(),
                attributes.material().exclude(),
                attributes.size().include(),
                attributes.material().include(),
                List.copyOf(includeTerms),
                condition.excludeTerms(),
                mustHaveStock
        );
    }

    private void appendAll(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(value);
            }
        }
    }
}
