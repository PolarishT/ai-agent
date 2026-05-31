package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 各 evidence searcher 共享的日志小工具：
 *
 * <ul>
 *   <li>{@link #flatten(String)} 把多行 SQL 压成一行，方便 logback 一条记录看完；</li>
 *   <li>{@link #summarize(java.util.List)} 把 hits 列表压成 {@code [{pid=12 score=4.80 type=PRODUCT_PROFILE} ...]}
 *       便于在 INFO 级日志里直接看到召回的商品 id 与分数，不用进 DB 翻；</li>
 *   <li>{@link #describeParams(MapSqlParameterSource)} 把 NamedParameterJdbcTemplate 的 params 转成可读 Map。</li>
 * </ul>
 */
final class EvidenceSearcherLogSupport {

    private static final int MAX_HITS_IN_SUMMARY = 10;

    private EvidenceSearcherLogSupport() {
    }

    static String flatten(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        return sql.replaceAll("\\s+", " ").trim();
    }

    static String summarize(List<ProductSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "[]";
        }
        List<String> entries = new ArrayList<>(Math.min(hits.size(), MAX_HITS_IN_SUMMARY));
        for (int i = 0; i < hits.size() && i < MAX_HITS_IN_SUMMARY; i++) {
            ProductSearchHit hit = hits.get(i);
            entries.add(String.format(
                    "{pid=%s score=%.3f type=%s%s}",
                    hit.productId(),
                    hit.score(),
                    hit.chunkType(),
                    hit.metadata() != null && hit.metadata().get("title") != null
                            ? " title=" + abbreviate(String.valueOf(hit.metadata().get("title")))
                            : ""
            ));
        }
        if (hits.size() > MAX_HITS_IN_SUMMARY) {
            entries.add("...and " + (hits.size() - MAX_HITS_IN_SUMMARY) + " more");
        }
        return "[" + String.join(", ", entries) + "]";
    }

    static Map<String, Object> describeParams(MapSqlParameterSource params) {
        if (params == null) {
            return Map.of();
        }
        return params.getValues().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue() == null ? "<null>" : entry.getValue(),
                        (a, b) -> a,
                        java.util.LinkedHashMap::new
                ));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= 30) {
            return text;
        }
        return text.substring(0, 30) + "…";
    }
}
