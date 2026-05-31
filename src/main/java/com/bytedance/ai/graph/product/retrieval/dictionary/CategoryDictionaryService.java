package com.bytedance.ai.graph.product.retrieval.dictionary;

import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 把品类名（categoryTerms / excludeCategoryTerms 中的中文名）映射成稳定 id。
 *
 * <p>当前 schema（{@code catalog_product.category} / {@code sub_category}）里没有独立的品类维表，
 * 因此本服务的 {@link #resolveIds(List)} 暂时只返回空列表，调用方按设计 fallback 到 name/path 过滤；
 * 一旦 catalog 域引入 {@code catalog_category} 维表，只需在此处替换实现，调用点（PostgreSQL filter builder /
 * Milvus scalar filter builder）的契约不变。
 *
 * <p>同时提供 {@link #knownNames(List)}：用于在生成 SQL/Milvus filter 前快速剔除显然不存在的品类名，
 * 减少无意义的 ILIKE 评估和 Milvus 表达式负担。
 */
@Component
public class CategoryDictionaryService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public CategoryDictionaryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 解析品类名 → 数字 id。
     *
     * <p>当前 MVP schema 没有 category 维表，固定返回空列表（caller fallback to name）。
     */
    public List<Long> resolveIds(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return List.of();
    }

    /**
     * 返回 {@code names} 中至少出现在 {@code catalog_product.category} 或 {@code sub_category} 里的子集。
     *
     * <p>当前实现走在线 DISTINCT 查询；后续可换成 Caffeine 缓存。
     */
    public List<String> knownNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        // intentionally simple: caller already经过 sanitizer/validator，这里只需通过 EXISTS-like 查询过滤
        String sql = """
                SELECT DISTINCT name
                  FROM (
                       SELECT lower(category) AS name FROM catalog_product
                        WHERE category IS NOT NULL
                        UNION ALL
                       SELECT lower(sub_category) AS name FROM catalog_product
                        WHERE sub_category IS NOT NULL
                       ) all_names
                 WHERE name IN (:names)
                """;
        List<String> lowered = names.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase())
                .distinct()
                .toList();
        if (lowered.isEmpty()) {
            return List.of();
        }
        try {
            return jdbcTemplate.queryForList(sql, java.util.Map.of("names", lowered), String.class);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }
}
