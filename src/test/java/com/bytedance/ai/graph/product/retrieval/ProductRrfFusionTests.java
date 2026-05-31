package com.bytedance.ai.graph.product.retrieval;

import com.bytedance.ai.shared.properties.RagProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRrfFusionTests {

    private final ProductRrfFusion fusion = new ProductRrfFusion(RagProperties.defaults());

    @Test
    void overlapAcrossBranchesBoostsRank() {
        ProductSearchHit a = hit(1L, "A", 0.9d);
        ProductSearchHit b = hit(2L, "B", 0.8d);
        ProductSearchHit c = hit(3L, "C", 0.7d);
        ProductSearchHit d = hit(4L, "D", 0.6d);

        List<ProductSearchHit> fused = fusion.fuse(
                List.of(List.of(a, b, c), List.of(a, d)),
                4
        );

        // A 在两路都靠前; B 仅在一路出现; C/D 各只一路
        assertThat(fused.get(0).productId()).isEqualTo(1L);
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
    }

    @Test
    void emptyBranchesReturnEmpty() {
        assertThat(fusion.fuse(List.of(), 5)).isEmpty();
        assertThat(fusion.fuse(List.of(List.of(), List.of()), 5)).isEmpty();
    }

    @Test
    void topKCapsResult() {
        List<ProductSearchHit> fused = fusion.fuse(
                List.of(List.of(hit(1L, "A", 1.0d), hit(2L, "B", 0.9d), hit(3L, "C", 0.8d))),
                2
        );
        assertThat(fused).hasSize(2);
    }

    private ProductSearchHit hit(Long productId, String externalRef, double score) {
        return new ProductSearchHit(productId, productId, externalRef, score, "PRODUCT_PROFILE", "snippet", Map.of());
    }
}
