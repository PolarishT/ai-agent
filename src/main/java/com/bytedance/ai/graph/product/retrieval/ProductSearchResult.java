package com.bytedance.ai.graph.product.retrieval;

import java.util.List;

/**
 * 商品检索 SPI 的分层结果。
 *
 * <p>graph product 模块一次性返回完整四层结果（keyword / semantic / fusion / rank），
 * 让上层 subgraph 节点可以分别消费、做可观测性展示。
 *
 * @param keywordHits   关键词路径召回，已经过硬过滤
 * @param semanticHits  语义路径召回，已经过硬过滤二次校验
 * @param fusedHits     RRF 融合后结果（去重 + reciprocal rank）
 * @param rankedHits    基础打分后结果（keyword/semantic/category/stock 组合权重）
 * @param degradedNotes 单分支失败 / 超时 / 限流等降级原因，可空但永不为 null
 */
public record ProductSearchResult(
        List<ProductSearchHit> keywordHits,
        List<ProductSearchHit> semanticHits,
        List<ProductSearchHit> fusedHits,
        List<ProductSearchHit> rankedHits,
        List<String> degradedNotes
) {

    public ProductSearchResult {
        keywordHits = copyOrEmpty(keywordHits);
        semanticHits = copyOrEmpty(semanticHits);
        fusedHits = copyOrEmpty(fusedHits);
        rankedHits = copyOrEmpty(rankedHits);
        degradedNotes = copyOrEmpty(degradedNotes);
    }

    public static ProductSearchResult empty() {
        return new ProductSearchResult(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static <T> List<T> copyOrEmpty(List<T> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }
}
