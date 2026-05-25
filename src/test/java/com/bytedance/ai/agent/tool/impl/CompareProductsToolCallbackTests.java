package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSkuView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.retrieval.spi.ProductSearchHit;
import com.bytedance.ai.retrieval.spi.ProductSearchRequest;
import com.bytedance.ai.retrieval.spi.ProductSearchSpi;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CompareProductsToolCallbackTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
    private final CapturingProductSearchSpi productSearchSpi = new CapturingProductSearchSpi();
    private final StubCatalogQueryFacade catalogQueryFacade = new StubCatalogQueryFacade();
    private final CompareProductsToolCallback callback = new CompareProductsToolCallback(
            productSearchSpi,
            catalogQueryFacade,
            jsonCodec,
            Schedulers.immediate()
    );

    @Test
    void exposesToolDefinitionAndCompareIntent() {
        assertThat(callback.getToolDefinition().name()).isEqualTo("compare_products");
        assertThat(callback.handles()).containsExactly(IntentType.COMPARE);
    }

    @Test
    void comparesExplicitSpuIdsAndExternalRefs() {
        catalogQueryFacade.put(spu(1L, "SPU-A", "A 面霜", "Alpha", "保湿强", "保湿", "强"));
        catalogQueryFacade.put(spu(2L, "SPU-B", "B 面霜", "Beta", "清爽", "保湿", "中"));

        CompareProductsToolCallback.CompareProductsOutput output = callback.compare(
                new CompareProductsToolCallback.CompareProductsInput(
                        "SPU-A 和 2 哪个保湿",
                        List.of(),
                        List.of(),
                        3,
                        List.of("保湿")
                )
        );

        assertThat(output.cards()).extracting("externalRef").containsExactly("SPU-A", "SPU-B");
        assertThat(output.compareMatrix()).isNotNull();
        assertThat(output.compareMatrix().rows()).extracting("attribute").contains("品牌", "价格", "库存", "保湿");
        assertThat(output.compareMatrix().recommendedRefId()).isEqualTo("#1");
    }

    @Test
    void resolvesFreeTextCandidatesBySearchAndKeepsOrder() {
        catalogQueryFacade.put(spu(1L, "SPU-A", "A 面霜", "Alpha", "保湿强", "保湿", "强"));
        catalogQueryFacade.put(spu(2L, "SPU-B", "B 面霜", "Beta", "价格友好", "保湿", "中"));
        productSearchSpi.hitsByQuery.put("A面霜", List.of(hit(1L, "SPU-A", 0.91d, "保湿强")));
        productSearchSpi.hitsByQuery.put("B面霜", List.of(hit(2L, "SPU-B", 0.82d, "价格友好")));

        CompareProductsToolCallback.CompareProductsOutput output = callback.compare(
                new CompareProductsToolCallback.CompareProductsInput(
                        "A面霜 vs B面霜 性价比",
                        List.of(),
                        List.of(),
                        3,
                        List.of()
                )
        );

        assertThat(productSearchSpi.queries).containsExactly("A面霜", "B面霜");
        assertThat(output.cards()).extracting("externalRef").containsExactly("SPU-A", "SPU-B");
        assertThat(output.compareMatrix().rows()).extracting("attribute").contains("性价比");
        assertThat(output.compareMatrix().recommendedRefId()).isEqualTo("#2");
    }

    @Test
    void fallsBackToBroadQueryForVagueTwoProductCompare() {
        catalogQueryFacade.put(spu(1L, "SPU-A", "A 防晒霜", "Alpha", "保湿强", "保湿", "强"));
        catalogQueryFacade.put(spu(2L, "SPU-B", "B 防晒霜", "Beta", "价格友好", "保湿", "中"));
        productSearchSpi.hitsByQuery.put("防晒霜", List.of(
                hit(1L, "SPU-A", 0.91d, "保湿强"),
                hit(2L, "SPU-B", 0.86d, "价格友好")
        ));

        CompareProductsToolCallback.CompareProductsOutput output = callback.compare(
                new CompareProductsToolCallback.CompareProductsInput(
                        "对比这两款防晒霜的保湿和性价比",
                        List.of(),
                        List.of(),
                        10,
                        List.of("保湿", "性价比")
                )
        );

        assertThat(productSearchSpi.queries).contains("防晒霜");
        assertThat(productSearchSpi.topKsByQuery).containsEntry("防晒霜", 2);
        assertThat(output.cards()).extracting("externalRef").containsExactly("SPU-A", "SPU-B");
        assertThat(output.compareMatrix()).isNotNull();
        assertThat(output.compareMatrix().rows()).extracting("attribute").contains("保湿", "性价比");
    }

    @Test
    void returnsCardsWithoutMatrixWhenOnlyOneProductResolved() {
        catalogQueryFacade.put(spu(1L, "SPU-A", "A 面霜", "Alpha", "保湿强", "保湿", "强"));

        CompareProductsToolCallback.CompareProductsOutput output = callback.compare(
                new CompareProductsToolCallback.CompareProductsInput(
                        "SPU-A 哪个保湿",
                        List.of(),
                        List.of(),
                        3,
                        List.of("保湿")
                )
        );

        assertThat(output.cards()).hasSize(1);
        assertThat(output.compareMatrix()).isNull();
    }

    private ProductSearchHit hit(Long spuId, String externalRef, double score, String snippet) {
        return new ProductSearchHit(spuId, 100L + spuId, externalRef, score, "TITLE", snippet, Map.of());
    }

    private CatalogSpuView spu(Long id, String externalRef, String title, String brand, String desc, String attrKey, String attrValue) {
        return new CatalogSpuView(
                id,
                externalRef,
                title,
                brand,
                "美妆/面霜",
                new BigDecimal(id == 1L ? "299" : "199"),
                new BigDecimal(id == 1L ? "299" : "199"),
                id == 1L ? 8 : 20,
                desc,
                List.of("https://img.example/" + externalRef + ".png"),
                null,
                Map.of(attrKey, attrValue),
                "DONE",
                "ACTIVE",
                1000L + id,
                List.<CatalogSkuView>of(),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private static class CapturingProductSearchSpi implements ProductSearchSpi {
        private final Map<String, List<ProductSearchHit>> hitsByQuery = new LinkedHashMap<>();
        private final Map<String, Integer> topKsByQuery = new LinkedHashMap<>();
        private final List<String> queries = new ArrayList<>();

        @Override
        public List<ProductSearchHit> search(ProductSearchRequest request) {
            queries.add(request.query());
            topKsByQuery.put(request.query(), request.topK());
            return hitsByQuery.getOrDefault(request.query(), List.of());
        }
    }

    private static class StubCatalogQueryFacade implements CatalogQueryFacade {
        private final Map<Long, CatalogSpuView> byId = new LinkedHashMap<>();
        private final Map<String, CatalogSpuView> byExternalRef = new LinkedHashMap<>();

        void put(CatalogSpuView spu) {
            byId.put(spu.id(), spu);
            byExternalRef.put(spu.externalRef(), spu);
        }

        @Override
        public CatalogSpuView getSpu(Long spuId) {
            CatalogSpuView spu = byId.get(spuId);
            if (spu == null) {
                throw new IllegalArgumentException("missing spu");
            }
            return spu;
        }

        @Override
        public Optional<CatalogSpuView> findSpuByExternalRef(String externalRef) {
            return Optional.ofNullable(byExternalRef.get(externalRef));
        }

        @Override
        public List<CatalogSkuView> listSkus(Long spuId) {
            return List.of();
        }
    }
}
