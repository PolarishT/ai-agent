package com.bytedance.ai.graph.product.retrieval;

import com.bytedance.ai.shared.properties.RagProperties;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 商品检索专用 RRF 融合器。算法与 {@link RagDocumentJoiner} 一致：
 * 同一 Product 在不同分支的排名贡献 {@code 1 / (k + rank + 1)} 累加为综合分。
 *
 * <p>独立类的原因：{@link RagDocumentJoiner} 工作在 chunk 维度，
 * 这里工作在 Product（{@link ProductSearchHit}）维度，复用一处会让两边都难演进。
 */
@Component
public class ProductRrfFusion {

    private final RagProperties ragProperties;

    public ProductRrfFusion(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public List<ProductSearchHit> fuse(List<List<ProductSearchHit>> branches, int topK) {
        if (branches == null || branches.isEmpty()) {
            return List.of();
        }
        double k = ragProperties.productQuery().rrfK();
        Map<String, Joined> joined = new LinkedHashMap<>();
        for (List<ProductSearchHit> branch : branches) {
            if (branch == null) {
                continue;
            }
            for (int rank = 0; rank < branch.size(); rank++) {
                ProductSearchHit hit = branch.get(rank);
                String key = key(hit);
                Joined accumulator = joined.computeIfAbsent(key, ignored -> new Joined(hit));
                accumulator.add(hit, rank, k);
            }
        }
        int effectiveTopK = Math.max(1, topK);
        return joined.values().stream()
                .sorted(Comparator.comparingDouble(Joined::fusedScore).reversed()
                        .thenComparing(j -> j.representative.documentId() == null ? Long.MAX_VALUE : j.representative.documentId()))
                .limit(effectiveTopK)
                .map(Joined::toHit)
                .toList();
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

    private static final class Joined {

        private ProductSearchHit representative;
        private double fusedScore;
        private double maxBranchScore;

        Joined(ProductSearchHit initial) {
            this.representative = initial;
            this.fusedScore = 0.0d;
            this.maxBranchScore = initial.score();
        }

        void add(ProductSearchHit hit, int rank, double k) {
            this.fusedScore += 1.0d / (k + rank + 1);
            if (hit.score() > this.maxBranchScore) {
                this.maxBranchScore = hit.score();
                this.representative = hit;
            }
        }

        double fusedScore() {
            return fusedScore;
        }

        ProductSearchHit toHit() {
            return new ProductSearchHit(
                    representative.productId(),
                    representative.documentId(),
                    representative.externalRef(),
                    fusedScore,
                    representative.chunkType(),
                    representative.snippet(),
                    representative.metadata()
            );
        }
    }
}
