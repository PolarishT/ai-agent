package com.bytedance.ai.graph.product.retrieval.filter;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.dictionary.BrandDictionaryService;
import com.bytedance.ai.graph.product.retrieval.dictionary.CategoryDictionaryService;
import com.bytedance.ai.shared.properties.RagProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 把 {@link ProductQueryCondition} 翻译为 Milvus scalar filter 表达式（boolean expression）。
 *
 * <p>语法参考 <a href="https://milvus.io/docs/zh/boolean.md">Milvus boolean expression</a>：
 * <ul>
 *   <li>逻辑连接：{@code &&} / {@code ||} / {@code not}；</li>
 *   <li>等值：{@code field == "..."} / {@code field == 12}；</li>
 *   <li>区间：{@code field >= 1}、{@code field <= 100}；</li>
 *   <li>集合：{@code field in ["a", "b"]} / {@code field not in [...]}。</li>
 * </ul>
 *
 * <p>本 builder 严格只对 {@link RagProperties.Milvus.ProductSchema} 中「配置了字段名」的维度生成 expr；
 * 未配置的维度直接跳过，由 PostgreSQL hard filter + hydrate 后 post-filter 兜底。
 *
 * <p>字符串值会按 Milvus 规则转义反斜杠和双引号。表达式为空时返回 {@code null}，
 * caller 应按 {@code SearchRequest.builder()} 的契约不调 {@code filterExpression}。
 */
@Component
public class MilvusScalarFilterBuilder {

    private final RagProperties ragProperties;
    private final CategoryDictionaryService categoryDictionaryService;
    private final BrandDictionaryService brandDictionaryService;

    public MilvusScalarFilterBuilder(
            RagProperties ragProperties,
            CategoryDictionaryService categoryDictionaryService,
            BrandDictionaryService brandDictionaryService
    ) {
        this.ragProperties = ragProperties;
        this.categoryDictionaryService = categoryDictionaryService;
        this.brandDictionaryService = brandDictionaryService;
    }

    /**
     * 构建 Milvus scalar filter expression。空字符串返回 {@code null}。
     */
    public String build(ProductQueryCondition condition) {
        if (condition == null) {
            return null;
        }
        RagProperties.Milvus.ProductSchema schema = ragProperties.milvus().productSchema();
        if (schema == null) {
            return null;
        }
        List<String> clauses = new ArrayList<>();

        appendStatus(clauses, schema);
        appendStock(clauses, schema, condition);
        appendPrice(clauses, schema, condition);
        appendCategory(clauses, schema, condition);
        appendBrand(clauses, schema, condition);

        if (clauses.isEmpty()) {
            return null;
        }
        return String.join(" && ", clauses);
    }

    private void appendStatus(List<String> clauses, RagProperties.Milvus.ProductSchema schema) {
        if (!StringUtils.hasText(schema.statusField()) || !StringUtils.hasText(schema.activeStatusValue())) {
            return;
        }
        clauses.add(schema.statusField() + " == " + quote(schema.activeStatusValue()));
    }

    private void appendStock(List<String> clauses, RagProperties.Milvus.ProductSchema schema, ProductQueryCondition condition) {
        if (!StringUtils.hasText(schema.stockField())) {
            return;
        }
        if (resolveMustHaveStock(condition)) {
            clauses.add(schema.stockField() + " > 0");
        }
    }

    private void appendPrice(List<String> clauses, RagProperties.Milvus.ProductSchema schema, ProductQueryCondition condition) {
        if (!StringUtils.hasText(schema.priceField())) {
            return;
        }
        BigDecimal priceMin = condition.priceMin();
        if (priceMin != null) {
            clauses.add(schema.priceField() + " >= " + priceMin.toPlainString());
        }
        BigDecimal priceMax = condition.priceMax();
        if (priceMax != null) {
            clauses.add(schema.priceField() + " <= " + priceMax.toPlainString());
        }
    }

    private void appendCategory(List<String> clauses, RagProperties.Milvus.ProductSchema schema, ProductQueryCondition condition) {
        List<String> includes = condition.categoryTerms();
        List<String> excludes = condition.excludeCategoryTerms();
        boolean hasIdField = StringUtils.hasText(schema.categoryIdField());
        boolean hasNameField = StringUtils.hasText(schema.categoryNameField());

        if (!includes.isEmpty()) {
            if (hasIdField) {
                List<Long> ids = categoryDictionaryService.resolveIds(includes);
                if (!ids.isEmpty()) {
                    clauses.add(schema.categoryIdField() + " in [" + joinLongs(ids) + "]");
                } else if (hasNameField) {
                    clauses.add(schema.categoryNameField() + " in [" + joinStrings(includes) + "]");
                }
            } else if (hasNameField) {
                clauses.add(schema.categoryNameField() + " in [" + joinStrings(includes) + "]");
            }
        }
        if (!excludes.isEmpty()) {
            if (hasIdField) {
                List<Long> ids = categoryDictionaryService.resolveIds(excludes);
                if (!ids.isEmpty()) {
                    clauses.add(schema.categoryIdField() + " not in [" + joinLongs(ids) + "]");
                } else if (hasNameField) {
                    clauses.add(schema.categoryNameField() + " not in [" + joinStrings(excludes) + "]");
                }
            } else if (hasNameField) {
                clauses.add(schema.categoryNameField() + " not in [" + joinStrings(excludes) + "]");
            }
        }
    }

    private void appendBrand(List<String> clauses, RagProperties.Milvus.ProductSchema schema, ProductQueryCondition condition) {
        List<String> includes = condition.brandTerms();
        List<String> excludes = condition.excludeBrandTerms();
        boolean hasIdField = StringUtils.hasText(schema.brandIdField());
        boolean hasNameField = StringUtils.hasText(schema.brandNameField());

        if (!includes.isEmpty()) {
            if (hasIdField) {
                List<Long> ids = brandDictionaryService.resolveIds(includes);
                if (!ids.isEmpty()) {
                    clauses.add(schema.brandIdField() + " in [" + joinLongs(ids) + "]");
                } else if (hasNameField) {
                    clauses.add(schema.brandNameField() + " in [" + joinStrings(includes) + "]");
                }
            } else if (hasNameField) {
                clauses.add(schema.brandNameField() + " in [" + joinStrings(includes) + "]");
            }
        }
        if (!excludes.isEmpty()) {
            if (hasIdField) {
                List<Long> ids = brandDictionaryService.resolveIds(excludes);
                if (!ids.isEmpty()) {
                    clauses.add(schema.brandIdField() + " not in [" + joinLongs(ids) + "]");
                } else if (hasNameField) {
                    clauses.add(schema.brandNameField() + " not in [" + joinStrings(excludes) + "]");
                }
            } else if (hasNameField) {
                clauses.add(schema.brandNameField() + " not in [" + joinStrings(excludes) + "]");
            }
        }
    }

    private boolean resolveMustHaveStock(ProductQueryCondition condition) {
        Boolean hint = condition.mustHaveStock();
        if (hint != null) {
            return hint;
        }
        return ragProperties.productQuery().mustHaveStockDefault();
    }

    private String joinLongs(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private String joinStrings(List<String> values) {
        return values.stream()
                .filter(StringUtils::hasText)
                .map(this::quote)
                .collect(Collectors.joining(", "));
    }

    private String quote(String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }
}
