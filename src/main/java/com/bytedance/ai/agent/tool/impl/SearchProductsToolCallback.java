package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.retrieval.spi.ProductSearchHit;
import com.bytedance.ai.retrieval.spi.ProductSearchRequest;
import com.bytedance.ai.retrieval.spi.ProductSearchSpi;
import com.bytedance.ai.shared.metadata.RagSearchFilter;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class SearchProductsToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "search_products";
    private static final int DEFAULT_TOP_K = 10;
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "query":{"type":"string","description":"用户商品检索查询"},
                "slots":{"type":"object","description":"Agent 已抽取的结构化槽位"},
                "topK":{"type":"integer","minimum":1,"maximum":10},
                "includeChunkTypes":{"type":"array","items":{"type":"string"}}
              },
              "required":["query"]
            }
            """;

    private final ProductSearchSpi productSearchSpi;
    private final CatalogQueryFacade catalogQueryFacade;
    private final RagJsonCodec jsonCodec;

    public SearchProductsToolCallback(
            ProductSearchSpi productSearchSpi,
            CatalogQueryFacade catalogQueryFacade,
            RagJsonCodec jsonCodec
    ) {
        this.productSearchSpi = productSearchSpi;
        this.catalogQueryFacade = catalogQueryFacade;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("基于关键词、价格区间、类目等在电商商品库内检索")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        SearchProductsInput input = jsonCodec.read(toolInput, SearchProductsInput.class);
        return jsonCodec.write(search(input));
    }

    public SearchProductsOutput search(SearchProductsInput input) {
        if (input == null || !StringUtils.hasText(input.query())) {
            throw new IllegalArgumentException("search_products.query 不能为空");
        }
        List<ProductSearchHit> hits = productSearchSpi.search(toRequest(input));
        List<SpuCardView> cards = enrichWithCatalog(hits);
        return new SearchProductsOutput(TOOL_NAME, cards, facetsApplied(input.slots()));
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.RECOMMEND_VAGUE, IntentType.FILTER_BY_ATTR, IntentType.REFINE);
    }

    private ProductSearchRequest toRequest(SearchProductsInput input) {
        return new ProductSearchRequest(
                buildQuery(input),
                RagSearchFilter.of("catalog://spu/", null, input.slots() == null ? null : input.slots().categoryHint()),
                effectiveTopK(input.topK()),
                input.includeChunkTypes() == null ? List.of() : input.includeChunkTypes()
        );
    }

    private String buildQuery(SearchProductsInput input) {
        if (input.slots() == null || input.slots().must().isEmpty()) {
            return input.query().trim();
        }
        return input.query().trim() + " " + String.join(" ", input.slots().must());
    }

    private int effectiveTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, DEFAULT_TOP_K);
    }

    private List<SpuCardView> enrichWithCatalog(List<ProductSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<SpuCardView> cards = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            ProductSearchHit hit = hits.get(i);
            Optional<CatalogSpuView> spu = resolveSpu(hit);
            String refId = "#" + (i + 1);
            cards.add(spu.map(view -> toCard(view, hit, refId))
                    .orElseGet(() -> fallbackCard(hit, refId)));
        }
        return cards;
    }

    private Optional<CatalogSpuView> resolveSpu(ProductSearchHit hit) {
        if (hit == null) {
            return Optional.empty();
        }
        if (hit.spuId() != null) {
            try {
                return Optional.of(catalogQueryFacade.getSpu(hit.spuId()));
            } catch (IllegalArgumentException ignored) {
                // 检索索引可能落后于 catalog，继续尝试 externalRef。
            }
        }
        if (StringUtils.hasText(hit.externalRef())) {
            return catalogQueryFacade.findSpuByExternalRef(hit.externalRef());
        }
        return Optional.empty();
    }

    private SpuCardView toCard(CatalogSpuView spu, ProductSearchHit hit, String refId) {
        return new SpuCardView(
                spu.id(),
                spu.externalRef(),
                spu.title(),
                spu.brand(),
                firstImage(spu.images()),
                spu.priceMin(),
                spu.priceMax(),
                spu.stock(),
                hit.score(),
                List.of(),
                reasons(hit),
                refId
        );
    }

    private SpuCardView fallbackCard(ProductSearchHit hit, String refId) {
        return new SpuCardView(
                hit.spuId(),
                hit.externalRef(),
                hit.externalRef(),
                null,
                null,
                null,
                null,
                null,
                hit.score(),
                List.of(),
                reasons(hit),
                refId
        );
    }

    private List<String> reasons(ProductSearchHit hit) {
        return StringUtils.hasText(hit.snippet()) ? List.of(hit.snippet()) : List.of();
    }

    private String firstImage(List<String> images) {
        return images == null || images.isEmpty() ? null : images.getFirst();
    }

    private Map<String, Object> facetsApplied(Slot slot) {
        Map<String, Object> facets = new LinkedHashMap<>();
        if (slot == null || slot.isEmpty()) {
            return facets;
        }
        if (slot.priceRange() != null && !slot.priceRange().isEmpty()) {
            facets.put("priceRange", slot.priceRange());
        }
        if (StringUtils.hasText(slot.categoryHint())) {
            facets.put("categoryHint", slot.categoryHint());
        }
        if (!slot.brands().isEmpty()) {
            facets.put("brands", slot.brands());
        }
        if (!slot.must().isEmpty()) {
            facets.put("must", slot.must());
        }
        return facets;
    }

    public record SearchProductsInput(
            String query,
            Slot slots,
            Integer topK,
            List<String> includeChunkTypes
    ) {
    }

    public record SearchProductsOutput(
            String toolName,
            List<SpuCardView> cards,
            Map<String, Object> facetsApplied
    ) {
        public SearchProductsOutput {
            cards = cards == null ? List.of() : List.copyOf(cards);
            facetsApplied = facetsApplied == null ? Map.of() : Map.copyOf(facetsApplied);
        }
    }
}
