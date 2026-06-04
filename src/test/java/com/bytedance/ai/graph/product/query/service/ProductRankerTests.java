package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.catalog.api.CatalogProductReviewView;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.product.query.ProductHydrationOptions;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.shared.properties.RagProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRankerTests {

    @Test
    void hydratesReviewsWhenOptionsIncludeReviews() {
        StubCatalog catalog = new StubCatalog();
        ProductRanker ranker = new ProductRanker(catalog, RagProperties.defaults());
        ProductHydrationOptions options = new ProductHydrationOptions(
                true, true, false, false, false, true, 0, 2);

        List<ProductSearchCandidate> candidates = ranker.refine(
                List.of(hit(23L)),
                List.of(hit(23L)),
                List.of(),
                ProductQueryCondition.empty("评价怎么样"),
                options
        );

        assertThat(catalog.reviewRequests).containsExactly(23L);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().reviews())
                .extracting("content")
                .containsExactly("冲泡很方便，风味比普通速溶自然");
    }

    @Test
    void skipsReviewHydrationWhenOptionsExcludeReviews() {
        StubCatalog catalog = new StubCatalog();
        ProductRanker ranker = new ProductRanker(catalog, RagProperties.defaults());

        List<ProductSearchCandidate> candidates = ranker.refine(
                List.of(hit(23L)),
                List.of(hit(23L)),
                List.of(),
                ProductQueryCondition.empty("评价怎么样"),
                ProductHydrationOptions.basic()
        );

        assertThat(catalog.reviewRequests).isEmpty();
        assertThat(candidates.getFirst().reviews()).isEmpty();
    }

    private ProductSearchHit hit(Long productId) {
        return new ProductSearchHit(productId, null, String.valueOf(productId), 0.8d,
                "REVIEW", null, Map.of());
    }

    private static final class StubCatalog implements CatalogQueryFacade {
        final java.util.List<Long> reviewRequests = new java.util.ArrayList<>();

        @Override
        public CatalogProductView getProduct(Long productId) {
            return new CatalogProductView(
                    productId,
                    "三顿半 数字星球系列 超即溶精品咖啡",
                    "三顿半",
                    "咖啡",
                    null,
                    new BigDecimal("129.00"),
                    new BigDecimal("129.00"),
                    new BigDecimal("129.00"),
                    10,
                    null,
                    "ACTIVE",
                    Map.of(),
                    Map.of(),
                    List.of(),
                    null,
                    null
            );
        }

        @Override
        public List<CatalogProductReviewView> listReviews(Long productId, int limit) {
            reviewRequests.add(productId);
            return List.of(new CatalogProductReviewView(
                    1L,
                    productId,
                    0,
                    "小林",
                    5,
                    "冲泡很方便，风味比普通速溶自然",
                    "POSITIVE",
                    Map.of(),
                    null,
                    null
            ));
        }

        @Override
        public List<CatalogSkuView> listSkus(Long productId) {
            return List.of();
        }
    }
}
