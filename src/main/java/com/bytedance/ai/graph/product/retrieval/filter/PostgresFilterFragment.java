package com.bytedance.ai.graph.product.retrieval.filter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PostgreSQL hard filter 的 SQL 片段 + 命名参数。
 *
 * <p>{@code sql} 以一系列以 {@code AND} 开头、可直接拼到现有 {@code WHERE} 子句末尾的语句构成；
 * caller 应保证已经写好 {@code WHERE p.status = 'ACTIVE'} 这类基础条件再追加本片段。
 *
 * <p>不可变：{@link #params()} 返回的 Map 不能修改。
 */
public record PostgresFilterFragment(String sql, Map<String, Object> params) {

    public PostgresFilterFragment {
        sql = sql == null ? "" : sql;
        params = params == null || params.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(params));
    }

    public boolean isEmpty() {
        return sql.isEmpty();
    }

    public static PostgresFilterFragment empty() {
        return new PostgresFilterFragment("", Map.of());
    }
}
