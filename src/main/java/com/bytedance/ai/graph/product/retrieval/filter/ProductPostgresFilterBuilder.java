package com.bytedance.ai.graph.product.retrieval.filter;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.retrieval.dictionary.BrandDictionaryService;
import com.bytedance.ai.graph.product.retrieval.dictionary.CategoryDictionaryService;
import com.bytedance.ai.shared.properties.RagProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 把 {@link ProductQueryCondition} 翻译为 PostgreSQL hard filter SQL 片段 + 命名参数。
 *
 * <h2>SQL 兼容性约束（重要）</h2>
 * <ul>
 *   <li><strong>不用 {@code ILIKE ANY(:list)}</strong>。
 *       NamedParameterJdbcTemplate 默认把 {@code List<String>} 绑成 IN-list 参数，
 *       PostgreSQL 端 {@code ANY(?)} 要求右侧是真正的 SQL array，会抛
 *       {@code op ANY/ALL (array) requires array on right side}。
 *       本 builder 改为动态展开成 {@code :xxxPattern0 / :xxxPattern1 / ...} 命名参数。</li>
 *   <li><strong>不用 {@code ILIKE ... ESCAPE '\'}</strong>。
 *       PostgreSQL 在 {@code LIKE ANY(...)} 形式下不支持 ESCAPE，且 {@code \} 本来就是
 *       LIKE 的默认转义字符。pattern 由 {@link #escapeLike(String)} 预转义。</li>
 * </ul>
 *
 * <h2>子句分工</h2>
 * <ul>
 *   <li><strong>状态 + 库存 + 价格</strong>：{@code catalog_sku} 的 EXISTS 子查询；</li>
 *   <li><strong>categoryTerms / brandTerms</strong>：作为 hard filter，category / sub_category / brand
 *       字段 OR 匹配；多个候选词之间也是 OR（"饮料 OR 气泡水"）；</li>
 *   <li><strong>includeTerms（软文本）</strong>：每个 term 一个 AND group，group 内字段 OR；
 *       与 categoryTerms / brandTerms 重叠的词不再放入软文本通道；</li>
 *   <li><strong>excludeTerms</strong>：NOT 包裹一组 OR，覆盖
 *       title / category / sub_category / brand / attributes_json / SKU properties_json 全字段。</li>
 * </ul>
 *
 * <p>列别名约定：caller 的主表必须以 {@code p} 起别名（{@code catalog_product p}）。
 */
@Component
public class ProductPostgresFilterBuilder {

    private static final String EXCLUDE_TERM_LOW_PRECISION = "酒精";

    private final RagProperties ragProperties;
    private final CategoryDictionaryService categoryDictionaryService;
    private final BrandDictionaryService brandDictionaryService;

    public ProductPostgresFilterBuilder(
            RagProperties ragProperties,
            CategoryDictionaryService categoryDictionaryService,
            BrandDictionaryService brandDictionaryService
    ) {
        this.ragProperties = ragProperties;
        this.categoryDictionaryService = categoryDictionaryService;
        this.brandDictionaryService = brandDictionaryService;
    }

    /**
     * 主入口：基于 catalog_product 主表的硬过滤片段。
     */
    public PostgresFilterFragment build(ProductQueryCondition condition) {
        if (condition == null) {
            return PostgresFilterFragment.empty();
        }
        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();

        appendStockAndPriceExists(sql, params, condition);
        appendPriceRange(sql, params, condition);
        appendCategory(sql, params, condition);
        appendBrand(sql, params, condition);
        appendIncludeTerms(sql, params, condition);
        appendExcludeTerms(sql, params, condition);

        return new PostgresFilterFragment(sql.toString(), params);
    }

    /**
     * 兼容历史调用方的 2 参数签名；新代码请直接调用 {@link #build(ProductQueryCondition)}。
     *
     * @param hasChunksJoin 已废弃；本 builder 不再引用 {@code rag_chunks}，该参数被忽略。
     */
    @Deprecated
    public PostgresFilterFragment build(ProductQueryCondition condition, boolean hasChunksJoin) {
        return build(condition);
    }

    private void appendStockAndPriceExists(StringBuilder sql, Map<String, Object> params, ProductQueryCondition condition) {
        boolean mustHaveStock = resolveMustHaveStock(condition);
        BigDecimal priceMin = condition.priceMin();
        BigDecimal priceMax = condition.priceMax();
        if (!mustHaveStock && priceMin == null && priceMax == null) {
            return;
        }
        StringBuilder existsSql = new StringBuilder("""
                AND EXISTS (
                    SELECT 1 FROM catalog_sku s
                     WHERE s.product_id = p.id
                       AND s.status = 'ACTIVE'
                """);
        if (mustHaveStock) {
            existsSql.append("       AND s.stock > 0\n");
        }
        if (priceMin != null) {
            existsSql.append("       AND s.price >= :priceMin\n");
            params.put("priceMin", priceMin);
        }
        if (priceMax != null) {
            existsSql.append("       AND s.price <= :priceMax\n");
            params.put("priceMax", priceMax);
        }
        existsSql.append(")\n");
        sql.append(existsSql);
    }

    private void appendPriceRange(StringBuilder sql, Map<String, Object> params, ProductQueryCondition condition) {
        if (condition.priceMin() != null) {
            sql.append("AND (p.price_max IS NULL OR p.price_max >= :priceMin)\n");
        }
        if (condition.priceMax() != null) {
            sql.append("AND (p.price_min IS NULL OR p.price_min <= :priceMax)\n");
        }
    }

    private void appendCategory(StringBuilder sql, Map<String, Object> params, ProductQueryCondition condition) {
        List<String> includes = condition.categoryTerms();
        List<String> excludes = condition.excludeCategoryTerms();
        if (!includes.isEmpty()) {
            List<Long> ids = categoryDictionaryService.resolveIds(includes);
            if (!ids.isEmpty()) {
                sql.append("AND p.id IN (:categoryIncludeIds)\n");
                params.put("categoryIncludeIds", ids);
            } else {
                List<String> paramNames = bindPatterns(params, "categoryIncludePattern", includes);
                if (!paramNames.isEmpty()) {
                    sql.append("AND (\n");
                    for (int i = 0; i < paramNames.size(); i++) {
                        if (i > 0) {
                            sql.append("    OR\n");
                        }
                        String name = paramNames.get(i);
                        sql.append("    (\n");
                        sql.append("        p.category ILIKE :").append(name).append("\n");
                        sql.append("     OR p.sub_category ILIKE :").append(name).append("\n");
                        sql.append("    )\n");
                    }
                    sql.append(")\n");
                }
            }
        }
        if (!excludes.isEmpty()) {
            List<Long> ids = categoryDictionaryService.resolveIds(excludes);
            if (!ids.isEmpty()) {
                sql.append("AND p.id NOT IN (:categoryExcludeIds)\n");
                params.put("categoryExcludeIds", ids);
            } else {
                List<String> paramNames = bindPatterns(params, "categoryExcludePattern", excludes);
                if (!paramNames.isEmpty()) {
                    sql.append("AND NOT (\n");
                    appendOrClausesAcrossFields(sql, paramNames,
                            "coalesce(p.category, '')",
                            "coalesce(p.sub_category, '')");
                    sql.append(")\n");
                }
            }
        }
    }

    private void appendBrand(StringBuilder sql, Map<String, Object> params, ProductQueryCondition condition) {
        List<String> includes = condition.brandTerms();
        List<String> excludes = condition.excludeBrandTerms();
        if (!includes.isEmpty()) {
            List<Long> ids = brandDictionaryService.resolveIds(includes);
            if (!ids.isEmpty()) {
                sql.append("AND p.id IN (:brandIncludeIds)\n");
                params.put("brandIncludeIds", ids);
            } else {
                List<String> paramNames = bindPatterns(params, "brandIncludePattern", includes);
                if (!paramNames.isEmpty()) {
                    sql.append("AND (\n");
                    for (int i = 0; i < paramNames.size(); i++) {
                        if (i > 0) {
                            sql.append("    OR\n");
                        }
                        sql.append("    p.brand ILIKE :").append(paramNames.get(i)).append("\n");
                    }
                    sql.append(")\n");
                }
            }
        }
        if (!excludes.isEmpty()) {
            List<Long> ids = brandDictionaryService.resolveIds(excludes);
            if (!ids.isEmpty()) {
                sql.append("AND p.id NOT IN (:brandExcludeIds)\n");
                params.put("brandExcludeIds", ids);
            } else {
                List<String> paramNames = bindPatterns(params, "brandExcludePattern", excludes);
                if (!paramNames.isEmpty()) {
                    sql.append("AND NOT (\n");
                    appendOrClausesAcrossFields(sql, paramNames, "coalesce(p.brand, '')");
                    sql.append(")\n");
                }
            }
        }
    }

    /**
     * includeTerms 软文本通道：每个 term 一个 AND group，group 内字段 OR。
     * 已经在 categoryTerms / brandTerms 出现过的词会被剔除避免双重过滤。
     */
    private void appendIncludeTerms(StringBuilder sql, Map<String, Object> params, ProductQueryCondition condition) {
        Set<String> alreadyHardFiltered = lowerCasedUnion(condition.categoryTerms(), condition.brandTerms());
        List<String> softTerms = new ArrayList<>();
        for (String term : condition.includeTerms()) {
            if (!StringUtils.hasText(term)) {
                continue;
            }
            String key = term.trim().toLowerCase(Locale.ROOT);
            if (alreadyHardFiltered.contains(key)) {
                continue;
            }
            softTerms.add(term.trim());
        }
        if (softTerms.isEmpty()) {
            return;
        }
        for (int i = 0; i < softTerms.size(); i++) {
            String paramName = "includePattern" + i;
            String alias = "s_inc_" + i;
            String pattern = "%" + escapeLike(softTerms.get(i)) + "%";
            sql.append("AND (\n");
            sql.append("    p.title ILIKE :").append(paramName).append("\n");
            sql.append(" OR coalesce(p.brand, '') ILIKE :").append(paramName).append("\n");
            sql.append(" OR coalesce(p.category, '') ILIKE :").append(paramName).append("\n");
            sql.append(" OR coalesce(p.sub_category, '') ILIKE :").append(paramName).append("\n");
            sql.append(" OR coalesce(p.attributes_json::text, '') ILIKE :").append(paramName).append("\n");
            sql.append(" OR EXISTS (\n");
            sql.append("        SELECT 1 FROM catalog_sku ").append(alias).append("\n");
            sql.append("         WHERE ").append(alias).append(".product_id = p.id\n");
            sql.append("           AND ").append(alias).append(".status = 'ACTIVE'\n");
            sql.append("           AND coalesce(").append(alias).append(".properties_json::text, '') ILIKE :")
                    .append(paramName).append("\n");
            sql.append("    )\n");
            sql.append(")\n");
            params.put(paramName, pattern);
        }
    }

    private void appendExcludeTerms(StringBuilder sql, Map<String, Object> params, ProductQueryCondition condition) {
        List<String> safeExcludes = condition.excludeTerms().stream()
                .filter(StringUtils::hasText)
                .filter(term -> !EXCLUDE_TERM_LOW_PRECISION.equalsIgnoreCase(term.trim()))
                .toList();
        if (safeExcludes.isEmpty()) {
            return;
        }
        List<String> paramNames = bindPatterns(params, "excludePattern", safeExcludes);
        if (paramNames.isEmpty()) {
            return;
        }
        // NOT 包裹「字段 × pattern」所有 OR 组合，再加 SKU properties EXISTS 子句。
        sql.append("AND NOT (\n");
        appendOrClausesAcrossFields(sql, paramNames,
                "coalesce(p.title, '')",
                "coalesce(p.category, '')",
                "coalesce(p.sub_category, '')",
                "coalesce(p.brand, '')",
                "coalesce(p.attributes_json::text, '')");
        sql.append("    OR EXISTS (\n");
        sql.append("        SELECT 1 FROM catalog_sku s_exc\n");
        sql.append("         WHERE s_exc.product_id = p.id\n");
        sql.append("           AND (\n");
        for (int i = 0; i < paramNames.size(); i++) {
            if (i > 0) {
                sql.append(" OR\n");
            }
            sql.append("                coalesce(s_exc.properties_json::text, '') ILIKE :")
                    .append(paramNames.get(i));
        }
        sql.append("\n           )\n");
        sql.append("    )\n");
        sql.append(")\n");
    }

    /**
     * 把若干个 pattern × 若干个字段交叉拼成 OR 子句，例如：
     *
     * <pre>
     *     coalesce(p.title, '') ILIKE :p0
     *  OR coalesce(p.title, '') ILIKE :p1
     *  OR coalesce(p.category, '') ILIKE :p0
     *  OR coalesce(p.category, '') ILIKE :p1
     * </pre>
     */
    private void appendOrClausesAcrossFields(StringBuilder sql, List<String> paramNames, String... fields) {
        boolean first = true;
        for (String field : fields) {
            for (String name : paramNames) {
                if (first) {
                    sql.append("    ");
                    first = false;
                } else {
                    sql.append(" OR ");
                }
                sql.append(field).append(" ILIKE :").append(name).append("\n");
            }
        }
    }

    /**
     * 把 terms 列表绑成 {@code prefix0 / prefix1 / ...} 命名参数，返回参数名顺序。
     */
    private List<String> bindPatterns(Map<String, Object> params, String prefix, List<String> terms) {
        List<String> names = new ArrayList<>();
        int index = 0;
        for (String term : terms) {
            if (!StringUtils.hasText(term)) {
                continue;
            }
            String name = prefix + index;
            String pattern = "%" + escapeLike(term.trim()) + "%";
            params.put(name, pattern);
            names.add(name);
            index++;
        }
        return names;
    }

    private boolean resolveMustHaveStock(ProductQueryCondition condition) {
        Boolean hint = condition.mustHaveStock();
        if (hint != null) {
            return hint;
        }
        return ragProperties.productQuery().mustHaveStockDefault();
    }

    private Set<String> lowerCasedUnion(List<String> a, List<String> b) {
        Set<String> set = new LinkedHashSet<>();
        if (a != null) {
            for (String s : a) {
                if (StringUtils.hasText(s)) {
                    set.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (b != null) {
            for (String s : b) {
                if (StringUtils.hasText(s)) {
                    set.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return set;
    }

    static String escapeLike(String term) {
        StringBuilder builder = new StringBuilder(term.length());
        for (int i = 0; i < term.length(); i++) {
            char ch = term.charAt(i);
            switch (ch) {
                case '\\', '%', '_' -> builder.append('\\').append(ch);
                default -> builder.append(ch);
            }
        }
        return builder.toString();
    }
}
