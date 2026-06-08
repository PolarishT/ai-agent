package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.catalog.api.CatalogProductReviewView;
import com.bytedance.ai.graph.catalog.api.CatalogProductSummaryView;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.product.query.ProductHydrationOptions;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.shared.properties.RagProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRankerTests {

    @Test
    void batchHydratesProductsAndReviewsOnce() {
        StubCatalog catalog = new StubCatalog();
        catalog.addProduct(summary(23L, "三顿半 数字星球系列 超即溶精品咖啡", "三顿半", "咖啡", 10, "129.00"));
        catalog.addProduct(summary(24L, "便携冷萃咖啡杯", "TestBrand", "杯具", 8, "79.00"));
        catalog.addProduct(summary(25L, "低因咖啡豆", "TestBrand", "咖啡", 6, "99.00"));
        catalog.addReview(review(23L, 0, "冲泡很方便，风味比普通速溶自然"));
        catalog.addReview(review(24L, 0, "杯盖密封不错"));
        ProductRanker ranker = new ProductRanker(catalog, RagProperties.defaults());
        ProductHydrationOptions options = new ProductHydrationOptions(
                true, true, false, false, false, true, 0, 2);

        List<ProductSearchCandidate> candidates = ranker.refine(
                List.of(hit(23L, 0.9d), hit(24L, 0.8d), hit(25L, 0.7d)),
                List.of(hit(23L, 0.9d), hit(24L, 0.8d), hit(25L, 0.7d)),
                List.of(),
                ProductQueryCondition.empty("评价怎么样"),
                options
        );

        assertThat(catalog.summaryRequests).containsExactly(List.of(23L, 24L, 25L));
        assertThat(catalog.reviewRequests).containsExactly(new ReviewRequest(List.of(23L, 24L, 25L), 2));
        assertThat(candidates).extracting(ProductSearchCandidate::productId)
                .containsExactly(23L, 24L, 25L);
        assertThat(candidates.getFirst().scoreFinal()).isEqualTo(0.9d);
        assertThat(candidates.getFirst().scoreKeyword()).isEqualTo(0.9d);
        assertThat(candidates.getFirst().matchReasons()).containsExactly("in_stock=10");
        assertThat(candidates.getFirst().reviews())
                .extracting("content")
                .containsExactly("冲泡很方便，风味比普通速溶自然");
        assertThat(candidates.get(2).reviews()).isEmpty();
    }

    @Test
    void skipsReviewHydrationWhenOptionsExcludeReviews() {
        StubCatalog catalog = new StubCatalog();
        catalog.addProduct(summary(23L, "三顿半 数字星球系列 超即溶精品咖啡", "三顿半", "咖啡", 10, "129.00"));
        ProductRanker ranker = new ProductRanker(catalog, RagProperties.defaults());

        List<ProductSearchCandidate> candidates = ranker.refine(
                List.of(hit(23L, 0.8d)),
                List.of(hit(23L, 0.8d)),
                List.of(),
                ProductQueryCondition.empty("评价怎么样"),
                ProductHydrationOptions.basic()
        );

        assertThat(catalog.summaryRequests).containsExactly(List.of(23L));
        assertThat(catalog.reviewRequests).isEmpty();
        assertThat(candidates.getFirst().reviews()).isEmpty();
    }

    @Test
    void dropsMissingBatchProductAndKeepsRemainingHits() {
        StubCatalog catalog = new StubCatalog();
        catalog.addProduct(summary(23L, "三顿半 数字星球系列 超即溶精品咖啡", "三顿半", "咖啡", 10, "129.00"));
        catalog.addProduct(summary(25L, "低因咖啡豆", "TestBrand", "咖啡", 6, "99.00"));
        ProductRanker ranker = new ProductRanker(catalog, RagProperties.defaults());

        List<ProductSearchCandidate> candidates = ranker.refine(
                List.of(hit(23L, 0.9d), hit(24L, 0.8d), hit(25L, 0.7d)),
                List.of(),
                List.of(),
                ProductQueryCondition.empty("咖啡"),
                ProductHydrationOptions.basic()
        );

        assertThat(catalog.summaryRequests).containsExactly(List.of(23L, 24L, 25L));
        assertThat(candidates).extracting(ProductSearchCandidate::productId)
                .containsExactly(23L, 25L);
    }

    private ProductSearchHit hit(Long productId, double score) {
        return new ProductSearchHit(productId, null, String.valueOf(productId), score,
                "REVIEW", null, Map.of());
    }

    private static final class StubCatalog implements CatalogQueryFacade {
        final List<List<Long>> summaryRequests = new ArrayList<>();
        final List<ReviewRequest> reviewRequests = new ArrayList<>();
        final Map<Long, CatalogProductSummaryView> products = new LinkedHashMap<>();
        final Map<Long, List<CatalogProductReviewView>> reviews = new LinkedHashMap<>();

        void addProduct(CatalogProductSummaryView product) {
            products.put(product.id(), product);
        }

        void addReview(CatalogProductReviewView review) {
            reviews.computeIfAbsent(review.productId(), ignored -> new ArrayList<>()).add(review);
        }

        @Override
        public CatalogProductView getProduct(Long productId) {
            throw new UnsupportedOperationException("ProductRanker should use batch summaries");
        }

        @Override
        public List<CatalogProductSummaryView> findProductSummaries(List<Long> productIds) {
            summaryRequests.add(List.copyOf(productIds));
            return productIds.stream()
                    .map(products::get)
                    .filter(Objects::nonNull)
                    .toList();
        }

        @Override
        public Map<Long, List<CatalogProductReviewView>> listReviewsByProductIds(
                List<Long> productIds,
                int limitPerProduct
        ) {
            reviewRequests.add(new ReviewRequest(List.copyOf(productIds), limitPerProduct));
            Map<Long, List<CatalogProductReviewView>> result = new LinkedHashMap<>();
            for (Long productId : productIds) {
                result.put(productId, reviews.getOrDefault(productId, List.of()).stream()
                        .limit(Math.max(limitPerProduct, 0))
                        .toList());
            }
            return result;
        }

        @Override
        public List<CatalogSkuView> listSkus(Long productId) {
            return List.of();
        }
    }

    private static CatalogProductSummaryView summary(
            Long productId,
            String title,
            String brand,
            String category,
            int stock,
            String price
    ) {
        return new CatalogProductSummaryView(
                productId,
                title,
                brand,
                category,
                null,
                new BigDecimal(price),
                new BigDecimal(price),
                stock,
                Map.of()
        );
    }

    private static CatalogProductReviewView review(Long productId, int reviewIndex, String content) {
        return new CatalogProductReviewView(
                (productId * 10) + reviewIndex,
                productId,
                reviewIndex,
                "小林",
                5,
                content,
                "POSITIVE",
                Map.of(),
                null,
                null
        );
    }

    private record ReviewRequest(List<Long> productIds, int limit) {
    }
}
