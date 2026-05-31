package com.bytedance.ai.graph.product.retrieval.dictionary;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 把品牌名映射成稳定 id。当前 {@code catalog_product.brand} 只是 VARCHAR，没有独立 brand 维表，
 * 因此 {@link #resolveIds(List)} 返回空列表，让 Postgres 走 ILIKE / Milvus 跳过该项 scalar filter。
 *
 * <p>未来引入 {@code catalog_brand} 维表后只需在此处替换实现。
 */
@Component
public class BrandDictionaryService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public BrandDictionaryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Long> resolveIds(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return List.of();
    }

    public List<String> knownNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> lowered = names.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase())
                .distinct()
                .toList();
        if (lowered.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT DISTINCT lower(brand) AS name
                  FROM catalog_product
                 WHERE brand IS NOT NULL
                   AND lower(brand) IN (:names)
                """;
        try {
            return jdbcTemplate.queryForList(sql, Map.of("names", lowered), String.class);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }
}
