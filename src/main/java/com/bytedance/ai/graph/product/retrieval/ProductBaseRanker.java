package com.bytedance.ai.graph.product.retrieval;

import com.bytedance.ai.shared.properties.RagProperties;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 商品检索基础打分：把 fused 阶段已经合并好的 Product 列表，再按
 * keyword / semantic / category / stock 四类信号做一次加权重排。
 *
 * <p>说明：keyword 与 semantic 是否命中靠 chunkType 来粗判，这里不要求
 * 命中精度（精排在 graph 侧 {@code ProductRanker.refine(...)} 里按 condition 二次调整）。
 *
 * <p>本类只用 retrieval 已知的信号；catalog 维度的 categoryMatch / stock 等需要
 * caller 提供 {@link ProductHardFilter} 上下文（用于判定 Product 是否落在白名单）。
 */
@Component
public class ProductBaseRanker {

    private final RagProperties ragProperties;

    public ProductBaseRanker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public List<ProductSearchHit> rank(
            List<ProductSearchHit> fusedHits,
            List<ProductSearchHit> keywordHits,
            List<ProductSearchHit> semanticHits,
            ProductHardFilter filter,
            int topK
    ) {
        if (fusedHits == null || fusedHits.isEmpty()) {
            return List.of();
        }
        RagProperties.ProductQuery.RankWeights weights = ragProperties.productQuery().rankWeights();
        Map<String, Double> keywordScore = indexByKey(keywordHits);
        Map<String, Double> semanticScore = indexByKey(semanticHits);

        int effectiveTopK = Math.max(1, topK);
        return fusedHits.stream()
                .map(hit -> new Scored(hit, computeScore(hit, keywordScore, semanticScore, filter, weights)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(s -> s.hit.documentId() == null ? Long.MAX_VALUE : s.hit.documentId()))
                .limit(effectiveTopK)
                .map(Scored::toHit)
                .toList();
    }

    private double computeScore(
            ProductSearchHit hit,
            Map<String, Double> keywordScore,
            Map<String, Double> semanticScore,
            ProductHardFilter filter,
            RagProperties.ProductQuery.RankWeights weights
    ) {
        String key = key(hit);
        double kw = keywordScore.getOrDefault(key, 0.0d);
        double sem = semanticScore.getOrDefault(key, 0.0d);
        double categoryBoost = (filter != null && !filter.categories().isEmpty()) ? 1.0d : 0.0d;
        double stockBoost = (filter != null && filter.mustHaveStock()) ? 1.0d : 0.0d;
        // hit.score() 此时是 RRF fused 分数；作为「关键词或语义命中过」的兜底信号
        double fusedSignal = hit.score();
        return weights.keyword() * normalize(kw)
                + weights.semantic() * normalize(sem)
                + weights.categoryMatch() * categoryBoost
                + weights.stock() * stockBoost
                + (weights.keyword() + weights.semantic()) * 0.5d * fusedSignal;
    }

    private Map<String, Double> indexByKey(List<ProductSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (ProductSearchHit hit : hits) {
            result.merge(key(hit), hit.score(), Math::max);
        }
        return result;
    }

    private String key(ProductSearchHit hit) {
        if (hit.productId() != null) {
            return "product:" + hit.productId();
        }
        if (hit.externalRef() != null) {
            return "ref:" + hit.externalRef();
        }
        return "doc:" + hit.documentId();
    }

    private double normalize(double rawScore) {
        if (rawScore <= 0) {
            return 0.0d;
        }
        return rawScore > 1.0d ? 1.0d : rawScore;
    }

    private record Scored(ProductSearchHit hit, double score) {
        ProductSearchHit toHit() {
            return new ProductSearchHit(
                    hit.productId(),
                    hit.documentId(),
                    hit.externalRef(),
                    score,
                    hit.chunkType(),
                    hit.snippet(),
                    hit.metadata()
            );
        }
    }
}
