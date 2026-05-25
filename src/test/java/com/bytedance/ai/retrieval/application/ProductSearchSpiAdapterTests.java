package com.bytedance.ai.retrieval.application;

import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSkuView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.retrieval.model.RagRetrievedChunk;
import com.bytedance.ai.retrieval.service.HybridRagRetriever;
import com.bytedance.ai.retrieval.service.RagRetrievalRequest;
import com.bytedance.ai.retrieval.spi.ProductSearchHit;
import com.bytedance.ai.retrieval.spi.ProductSearchRequest;
import com.bytedance.ai.shared.metadata.RagSearchFilter;
import com.bytedance.ai.shared.properties.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSearchSpiAdapterTests {

    @Test
    void overFetchesChunksBeforeAggregatingToProductHits() {
        StubHybridRagRetriever retriever = new StubHybridRagRetriever(List.of(
                chunk(1L, 1L, "catalog://spu/SPU-A", 0.91d),
                chunk(2L, 1L, "catalog://spu/SPU-A", 0.89d),
                chunk(3L, 2L, "catalog://spu/SPU-B", 0.82d)
        ));
        ProductSearchSpiAdapter adapter = new ProductSearchSpiAdapter(
                provider(retriever),
                provider(null),
                new StubCatalogQueryFacade(),
                RagProperties.defaults()
        );

        List<ProductSearchHit> hits = adapter.search(new ProductSearchRequest(
                "防晒霜",
                RagSearchFilter.of("catalog://spu/", null, null),
                2,
                List.of()
        ));

        assertThat(retriever.requests).hasSize(1);
        assertThat(retriever.requests.getFirst().budget().perQueryTopK()).isEqualTo(10);
        assertThat(hits).extracting(ProductSearchHit::externalRef).containsExactly("SPU-A", "SPU-B");
    }

    private static RagRetrievedChunk chunk(Long chunkId, Long documentId, String sourceUri, Double score) {
        return new RagRetrievedChunk(
                chunkId,
                documentId,
                "title",
                "catalog-spu",
                sourceUri,
                chunkId.intValue(),
                score,
                "content",
                List.of(),
                "text",
                null
        );
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

    private static class StubHybridRagRetriever extends HybridRagRetriever {
        private final List<RagRetrievedChunk> results;
        private final List<RagRetrievalRequest> requests = new ArrayList<>();

        @SuppressWarnings("deprecation")
        StubHybridRagRetriever(List<RagRetrievedChunk> results) {
            super(null, null, null, Runnable::run, RagProperties.defaults(), null);
            this.results = results;
        }

        @Override
        public List<RagRetrievedChunk> search(RagRetrievalRequest request) {
            requests.add(request);
            return results;
        }
    }

    private static class StubCatalogQueryFacade implements CatalogQueryFacade {
        private final Map<String, CatalogSpuView> byExternalRef = new LinkedHashMap<>();

        StubCatalogQueryFacade() {
            put(spu(1L, "SPU-A"));
            put(spu(2L, "SPU-B"));
        }

        private void put(CatalogSpuView spu) {
            byExternalRef.put(spu.externalRef(), spu);
        }

        @Override
        public CatalogSpuView getSpu(Long spuId) {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public Optional<CatalogSpuView> findSpuByExternalRef(String externalRef) {
            return Optional.ofNullable(byExternalRef.get(externalRef));
        }

        @Override
        public List<CatalogSkuView> listSkus(Long spuId) {
            return List.of();
        }

        private CatalogSpuView spu(Long id, String externalRef) {
            return new CatalogSpuView(
                    id,
                    externalRef,
                    externalRef,
                    "brand",
                    "category",
                    BigDecimal.ONE,
                    BigDecimal.TEN,
                    10,
                    "desc",
                    List.of(),
                    null,
                    Map.of(),
                    "DONE",
                    "ACTIVE",
                    id,
                    List.of(),
                    OffsetDateTime.now(),
                    OffsetDateTime.now()
            );
        }
    }
}
