package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.shared.metadata.RagChunkType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多个 evidence searcher 聚合后的结果。
 *
 * <p>KeywordRetrievalRouter 按 {@link RagChunkType} 调度 searcher 后，把每路 hits 累加到这里。
 * 同一 productId 可能在多个 evidence 通道命中（如 PRODUCT_PROFILE 与 MARKETING 都打中），
 * 本类提供两种视图：
 * <ul>
 *   <li>{@link #hits()} —— 全部原始命中，按 router 调度顺序排列；</li>
 *   <li>{@link #distinctByProductId()} —— 按 productId 去重，保留最高分一条，并把所有命中过的
 *       chunk_type 写入新 hit 的 {@code metadata.evidenceTypes}，方便下游 ranker / 展示。</li>
 * </ul>
 *
 * <p>每个 {@link RagChunkType} 贡献的命中数也会被记录，便于可观测性。
 */
public final class KeywordSearchResult {

    private final List<ProductSearchHit> hits = new ArrayList<>();
    private final EnumMap<RagChunkType, Integer> countByType = new EnumMap<>(RagChunkType.class);

    KeywordSearchResult() {
    }

    public static KeywordSearchResult empty() {
        return new KeywordSearchResult();
    }

    /**
     * 累加一批 hit 并记录该 chunk_type 贡献量。
     *
     * @return self，方便链式调用
     */
    public KeywordSearchResult addAll(RagChunkType type, List<ProductSearchHit> additional) {
        if (additional == null || additional.isEmpty()) {
            return this;
        }
        hits.addAll(additional);
        countByType.merge(type, additional.size(), Integer::sum);
        return this;
    }

    /**
     * 当前累计的所有原始 hit，按插入顺序返回（router 调度顺序）。
     */
    public List<ProductSearchHit> hits() {
        return List.copyOf(hits);
    }

    /**
     * 按 productId 去重，同一 product 只保留最高分一条；新 hit 的
     * {@code metadata.evidenceTypes} 标记该 product 在哪些 evidence 通道命中过，
     * 比如 {@code [PRODUCT_PROFILE, MARKETING]}。
     *
     * <p>productId 为 null 的 hit 不会进入去重结果。
     */
    public List<ProductSearchHit> distinctByProductId() {
        Map<Long, ProductSearchHit> bestByPid = new LinkedHashMap<>();
        Map<Long, Set<String>> evidenceTypesByPid = new LinkedHashMap<>();
        for (ProductSearchHit hit : hits) {
            Long pid = hit.productId();
            if (pid == null) {
                continue;
            }
            evidenceTypesByPid.computeIfAbsent(pid, k -> new LinkedHashSet<>()).add(hit.chunkType());
            ProductSearchHit current = bestByPid.get(pid);
            if (current == null || hit.score() > current.score()) {
                bestByPid.put(pid, hit);
            }
        }
        List<ProductSearchHit> result = new ArrayList<>(bestByPid.size());
        for (Map.Entry<Long, ProductSearchHit> entry : bestByPid.entrySet()) {
            ProductSearchHit best = entry.getValue();
            Set<String> types = evidenceTypesByPid.get(entry.getKey());
            Map<String, Object> meta = new LinkedHashMap<>(best.metadata());
            meta.put("evidenceTypes", List.copyOf(types));
            result.add(new ProductSearchHit(
                    best.productId(),
                    best.documentId(),
                    best.externalRef(),
                    best.score(),
                    best.chunkType(),
                    best.snippet(),
                    meta
            ));
        }
        return result;
    }

    /**
     * 在 ≥ 2 个 evidence 通道命中过的 product 数量；用于可观测性。
     */
    public int multiSourceProductCount() {
        Map<Long, Set<String>> byPid = new LinkedHashMap<>();
        for (ProductSearchHit hit : hits) {
            if (hit.productId() == null) {
                continue;
            }
            byPid.computeIfAbsent(hit.productId(), k -> new LinkedHashSet<>()).add(hit.chunkType());
        }
        int multi = 0;
        for (Set<String> types : byPid.values()) {
            if (types.size() > 1) {
                multi++;
            }
        }
        return multi;
    }

    /**
     * 每个 chunk_type 命中的数量；用于日志与可观测性。
     */
    public Map<RagChunkType, Integer> countByType() {
        return Collections.unmodifiableMap(new EnumMap<>(countByType));
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    /**
     * 不可变快照，避免调用方误改内部状态。
     */
    public Snapshot snapshot() {
        return new Snapshot(List.copyOf(hits), Collections.unmodifiableMap(new EnumMap<>(countByType)));
    }

    public record Snapshot(List<ProductSearchHit> hits, Map<RagChunkType, Integer> countByType) {
        public Snapshot {
            hits = hits == null ? List.of() : List.copyOf(hits);
            countByType = countByType == null ? Map.of() : Map.copyOf(countByType);
        }
    }
}
