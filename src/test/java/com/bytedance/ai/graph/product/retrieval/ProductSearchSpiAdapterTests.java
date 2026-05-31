package com.bytedance.ai.graph.product.retrieval;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import com.bytedance.ai.shared.properties.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSearchSpiAdapterTests {

    @Test
    void searchProductFusesKeywordAndSemanticBranches() {
        ProductSearchHit keywordOnly = hit(101L, 1L, 0.8d, "FAQ_QUERY");
        ProductSearchHit overlapKeyword = hit(102L, 2L, 0.7d, "PRODUCT_PROFILE");
        ProductSearchHit overlapSemantic = hit(102L, 3L, 0.9d, "REVIEW");
        ProductSearchSpiAdapter adapter = adapter(
                new FixedKeywordRetriever(List.of(keywordOnly, overlapKeyword)),
                new FixedSemanticRetriever(List.of(overlapSemantic))
        );

        ProductSearchResult result = adapter.searchProduct(new ProductSearchRequest(
                "防晒霜",
                2,
                ProductHardFilter.empty(),
                "适合敏感肌的防晒霜"
        ));

        assertThat(result.keywordHits()).extracting(ProductSearchHit::productId)
                .containsExactly(101L, 102L);
        assertThat(result.semanticHits()).extracting(ProductSearchHit::productId)
                .containsExactly(102L);
        assertThat(result.rankedHits()).extracting(ProductSearchHit::productId)
                .contains(102L);
        assertThat(result.degradedNotes()).isEmpty();
    }

    @Test
    void semanticFailureReturnsKeywordOnlyWithDegradedNotice() {
        ProductSearchSpiAdapter adapter = adapter(
                new FixedKeywordRetriever(List.of(hit(101L, 1L, 0.8d, "FAQ_QUERY"))),
                new FailingSemanticRetriever()
        );

        ProductSearchResult result = adapter.searchProduct(new ProductSearchRequest(
                "防晒霜",
                3,
                ProductHardFilter.empty(),
                "适合敏感肌的防晒霜"
        ));

        assertThat(result.keywordHits()).hasSize(1);
        assertThat(result.semanticHits()).isEmpty();
        assertThat(result.rankedHits()).extracting(ProductSearchHit::productId)
                .containsExactly(101L);
        assertThat(result.degradedNotes()).contains("semantic_unavailable");
    }

    private ProductSearchSpiAdapter adapter(
            ProductKeywordRagRetriever keywordRetriever,
            ProductSemanticRagRetriever semanticRetriever
    ) {
        RagProperties properties = RagProperties.defaults();
        return new ProductSearchSpiAdapter(
                keywordRetriever,
                provider(semanticRetriever),
                new ProductRrfFusion(properties),
                new ProductBaseRanker(properties),
                properties,
                Runnable::run
        );
    }

    private static ProductSearchHit hit(Long productId, Long documentId, double score, String chunkType) {
        return new ProductSearchHit(productId, documentId, String.valueOf(productId), score, chunkType, "snippet", Map.of());
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private static class FixedKeywordRetriever extends ProductKeywordRagRetriever {

        private final List<ProductSearchHit> hits;

        FixedKeywordRetriever(List<ProductSearchHit> hits) {
            super(null, null);
            this.hits = hits;
        }

        @Override
        public List<ProductSearchHit> search(
                String query, ProductQueryCondition condition, ProductQueryIntent intent, int topK
        ) {
            return hits;
        }
    }

    private static class FixedSemanticRetriever extends ProductSemanticRagRetriever {

        private final List<ProductSearchHit> hits;

        FixedSemanticRetriever(List<ProductSearchHit> hits) {
            super(null, null, RagProperties.defaults(), null, null);
            this.hits = hits;
        }

        @Override
        public List<ProductSearchHit> search(String query, ProductQueryCondition condition, int topK) {
            return hits;
        }
    }

    private static class FailingSemanticRetriever extends ProductSemanticRagRetriever {

        FailingSemanticRetriever() {
            super(null, null, RagProperties.defaults(), null, null);
        }

        @Override
        public List<ProductSearchHit> search(String query, ProductQueryCondition condition, int topK) {
            throw new IllegalStateException("milvus unavailable");
        }
    }
}
