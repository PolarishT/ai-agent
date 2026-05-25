package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSkuView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.retrieval.spi.ProductSearchHit;
import com.bytedance.ai.retrieval.spi.ProductSearchRequest;
import com.bytedance.ai.retrieval.spi.ProductSearchSpi;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompareProductsToolCallbackTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
    private CapturingProductSearchSpi productSearchSpi;
    private StubCatalogQueryFacade catalogQueryFacade;
    private CompareProductsService service;
    private CompareProductsToolCallback callback;

    @BeforeEach
    void setUp() {
        productSearchSpi = new CapturingProductSearchSpi();
        catalogQueryFacade = new StubCatalogQueryFacade();
        service = new CompareProductsService(
                new ProductCandidateResolver(catalogQueryFacade, productSearchSpi),
                new CompareMatrixBuilder()
        );
        callback = new CompareProductsToolCallback(service, jsonCodec);
    }

    @Test
    void exposesToolDefinitionAndCompareIntent() {
        assertThat(callback.getToolDefinition().name()).isEqualTo("compare_products");
        assertThat(callback.getToolDefinition().inputSchema()).contains(
                "query",
                "spuIds",
                "externalRefs",
                "productKeywords",
                "compareAspects",
                "userGoal",
                "禁止编造价格"
        );
        assertThat(callback.handles()).containsExactly(IntentType.COMPARE);
    }

    @Test
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> service.compare(new CompareProductsInput(
                " ",
                List.of(),
                List.of(),
                List.of(),
                3,
                List.of(),
                null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void resolvesExplicitSpuIds() {
        catalogQueryFacade.put(spu(1L, "SPU-A", "A 面霜", "Alpha", "保湿强", "保湿", "强"));
        catalogQueryFacade.put(spu(2L, "SPU-B", "B 面霜", "Beta", "清爽", "保湿", "中"));

        CompareProductsOutput output = service.compare(new CompareProductsInput(
                "对比这两个商品",
                List.of(1L, 2L),
                List.of(),
                List.of(),
                3,
                List.of("保湿"),
                "综合推荐"
        ));

        assertThat(output.cards()).extracting("externalRef").containsExactly("SPU-A", "SPU-B");
        assertThat(productSearchSpi.queries).isEmpty();
        assertThat(output.compareMatrix()).isNotNull();
        assertThat(output.compareMatrix().rows()).extracting("attribute").contains("品牌", "价格", "库存", "保湿");
    }

    @Test
    void resolvesExplicitExternalRefs() {
        catalogQueryFacade.put(spu(1L, "SPU-A", "A 面霜", "Alpha", "保湿强", "保湿", "强"));
        catalogQueryFacade.put(spu(2L, "SPU-B", "B 面霜", "Beta", "清爽", "保湿", "中"));

        CompareProductsOutput output = service.compare(new CompareProductsInput(
                "SPU-A 和 SPU-B 哪个更好",
                List.of(),
                List.of("SPU-A", "SPU-B"),
                List.of(),
                3,
                List.of("品牌"),
                null
        ));

        assertThat(output.cards()).extracting("spuId").containsExactly(1L, 2L);
        assertThat(productSearchSpi.queries).isEmpty();
        assertThat(output.compareMatrix()).isNotNull();
    }

    @Test
    void resolvesProductKeywordsBySearch() {
        catalogQueryFacade.put(spu(1L, "SPU-A", "A 面霜", "Alpha", "保湿强", "保湿", "强"));
        catalogQueryFacade.put(spu(2L, "SPU-B", "B 面霜", "Beta", "价格友好", "保湿", "中"));
        productSearchSpi.hitsByQuery.put("A 面霜", List.of(hit(1L, "SPU-A", 0.91d, "命中 A 面霜")));
        productSearchSpi.hitsByQuery.put("B 面霜", List.of(hit(2L, "SPU-B", 0.82d, "命中 B 面霜")));

        CompareProductsOutput output = service.compare(new CompareProductsInput(
                "A 面霜 vs B 面霜",
                List.of(),
                List.of(),
                List.of("A 面霜", "B 面霜"),
                3,
                List.of("保湿"),
                null
        ));

        assertThat(productSearchSpi.queries).containsExactly("A 面霜", "B 面霜");
        assertThat(output.cards()).extracting("externalRef").containsExactly("SPU-A", "SPU-B");
    }

    @Test
    void compareAspectsAreAddedToMatrix() {
        catalogQueryFacade.put(spu(1L, "SPU-A", "A 精华", "Alpha", "含烟酰胺，肤感清爽", "成分", "烟酰胺"));
        catalogQueryFacade.put(spu(2L, "SPU-B", "B 精华", "Beta", "肤感滋润", "成分", "玻色因"));

        CompareProductsOutput output = service.compare(new CompareProductsInput(
                "对比成分和肤感",
                List.of(1L, 2L),
                List.of(),
                List.of(),
                3,
                List.of("成分", "肤感"),
                "敏感肌优先"
        ));

        assertThat(output.compareMatrix().rows()).extracting("attribute").contains("成分", "肤感");
        assertThat(output.compareMatrix().rows().stream()
                .filter(row -> row.attribute().equals("成分"))
                .findFirst()
                .orElseThrow()
                .values()).containsExactly("烟酰胺", "玻色因");
    }

    @Test
    void topKIsCappedAtFive() {
        for (long i = 1; i <= 6; i++) {
            String externalRef = "SPU-" + i;
            catalogQueryFacade.put(spu(i, externalRef, "商品 " + i, "Brand", "描述", "功效", "保湿"));
        }
        productSearchSpi.hitsByQuery.put("面霜", List.of(
                hit(1L, "SPU-1", 0.99d, "1"),
                hit(2L, "SPU-2", 0.98d, "2"),
                hit(3L, "SPU-3", 0.97d, "3"),
                hit(4L, "SPU-4", 0.96d, "4"),
                hit(5L, "SPU-5", 0.95d, "5"),
                hit(6L, "SPU-6", 0.94d, "6")
        ));

        CompareProductsOutput output = service.compare(new CompareProductsInput(
                "对比面霜",
                List.of(),
                List.of(),
                List.of("面霜"),
                10,
                List.of(),
                null
        ));

        assertThat(productSearchSpi.topKsByQuery).containsEntry("面霜", 5);
        assertThat(output.cards()).hasSize(5);
    }

    @Test
    void returnsCardsWithoutMatrixWhenOnlyOneProductResolved() {
        catalogQueryFacade.put(spu(1L, "SPU-A", "A 面霜", "Alpha", "保湿强", "保湿", "强"));

        CompareProductsOutput output = service.compare(new CompareProductsInput(
                "对比 A 面霜",
                List.of(1L),
                List.of(),
                List.of(),
                3,
                List.of("保湿"),
                null
        ));

        assertThat(output.cards()).hasSize(1);
        assertThat(output.compareMatrix()).isNull();
    }

    @Test
    void callbackReadsInputAndWritesCompatibleOutput() {
        catalogQueryFacade.put(spu(1L, "SPU-A", "A 面霜", "Alpha", "保湿强", "保湿", "强"));
        catalogQueryFacade.put(spu(2L, "SPU-B", "B 面霜", "Beta", "清爽", "保湿", "中"));

        String outputJson = callback.call("""
                {
                  "query":"对比两款面霜",
                  "spuIds":[1,2],
                  "compareAspects":["保湿"],
                  "userGoal":"综合推荐"
                }
                """);

        CompareProductsOutput output = jsonCodec.read(outputJson, CompareProductsOutput.class);
        assertThat(output.toolName()).isEqualTo("compare_products");
        assertThat(output.cards()).hasSize(2);
        assertThat(output.compareMatrix()).isNotNull();
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
