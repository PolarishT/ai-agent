package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.slot.NegationRerankFilter;
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

/**
 * 商品检索工具：实现 Spring AI 的 {@link org.springframework.ai.tool.ToolCallback}，
 * W1/W2 由 {@code AgentTurnService} 程序确定性调用（不让 LLM 选）。
 *
 * <p>关键链路：
 * <pre>
 * Slot + memory.lastTurnSpuRefs
 *   → ProductSearchSpi 召回（反选场景多召回到 50 条）
 *   → NegationRerankFilter LLM 二分类剔除假阳
 *   → 截到 topK
 *   → CatalogQueryFacade 回填实时 price/stock 拼成 SpuCardView
 * </pre>
 *
 * <p>对外契约（{@link SearchProductsOutput}）：cards + facetsApplied + excludedFacets，
 * 上游 {@link com.bytedance.ai.agent.application.AgentTurnService} 据此构造 tool.result 事件。
 */
@Component
public class SearchProductsToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "search_products";
    /** 客户端可见 topK 上限；超过该值卡片屏幕渲染压力大且 LLM 引用 [#N] 容易混乱。 */
    private static final int DEFAULT_TOP_K = 10;
    /** 反选场景先多召回，再 rerank 剔假阳；避免 mustNot 把候选集压得太薄。 */
    private static final int RECALL_TOP_K_FOR_NEGATION = 50;
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "query":{"type":"string","description":"用户商品检索查询"},
                "slots":{"type":"object","description":"Agent 已抽取的结构化槽位（含 mustNot）"},
                "topK":{"type":"integer","minimum":1,"maximum":10},
                "includeChunkTypes":{"type":"array","items":{"type":"string"}},
                "restrictToSpuRefs":{"type":"array","items":{"type":"string"}}
              },
              "required":["query"]
            }
            """;

    private final ProductSearchSpi productSearchSpi;
    private final CatalogQueryFacade catalogQueryFacade;
    private final NegationRerankFilter negationRerankFilter;
    private final RagJsonCodec jsonCodec;

    public SearchProductsToolCallback(
            ProductSearchSpi productSearchSpi,
            CatalogQueryFacade catalogQueryFacade,
            NegationRerankFilter negationRerankFilter,
            RagJsonCodec jsonCodec
    ) {
        this.productSearchSpi = productSearchSpi;
        this.catalogQueryFacade = catalogQueryFacade;
        this.negationRerankFilter = negationRerankFilter;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("基于关键词、价格区间、类目、反选属性等在电商商品库内检索")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        SearchProductsInput input = jsonCodec.read(toolInput, SearchProductsInput.class);
        return jsonCodec.write(search(input));
    }

    /**
     * 类型化入口：调用方拼装 {@link SearchProductsInput}（含 Slot），返回结构化 output。
     *
     * <p>反选路径与正向路径的差异在 {@link #toRequest} 与 rerank 阶段：
     * <ul>
     *   <li>正向：直接召回 topK，无 rerank；</li>
     *   <li>反向：召回 50 条（{@link #RECALL_TOP_K_FOR_NEGATION}）→ LLM 二分类剔假阳 → 截到 topK，
     *       同时产出 {@code excludedFacets} 给客户端展示「已为您排除…」。</li>
     * </ul>
     */
    public SearchProductsOutput search(SearchProductsInput input) {
        if (input == null || !StringUtils.hasText(input.query())) {
            throw new IllegalArgumentException("search_products.query 不能为空");
        }
        Slot.MustNot mustNot = mustNotOf(input.slots());
        List<ProductSearchHit> hits = productSearchSpi.search(toRequest(input, mustNot));
        List<String> excludedFacets = List.of();
        if (!mustNot.isEmpty() && !hits.isEmpty()) {
            // top-50 召回再交给 LLM 精过滤，弥补 Milvus 表达式无法理解语义否定（如"无酒精"也含"酒精"字串）。
            NegationRerankFilter.Result reranked = negationRerankFilter.apply(hits, mustNot);
            hits = reranked.keepHits();
            excludedFacets = reranked.excludedFacets();
        }
        // 截到对外的 topK；rerank 之前用 recall 大池子。
        int finalTopK = effectiveTopK(input.topK());
        if (hits.size() > finalTopK) {
            hits = hits.subList(0, finalTopK);
        }
        List<SpuCardView> cards = enrichWithCatalog(hits);
        return new SearchProductsOutput(TOOL_NAME, cards, facetsApplied(input.slots()), excludedFacets);
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.RECOMMEND_VAGUE, IntentType.FILTER_BY_ATTR, IntentType.REFINE);
    }

    /**
     * 把 agent 侧的 {@link SearchProductsInput} 翻译成 retrieval 的 {@link ProductSearchRequest}：
     * sourceUriPrefix 固定写死成 {@code catalog://spu/}（agent 只检索商品索引，不与其它知识库混淆）；
     * categoryHint 复用 headingPathContains 做轻量类目过滤；mustNot 拆三桶推到 retrieval。
     */
    private ProductSearchRequest toRequest(SearchProductsInput input, Slot.MustNot mustNot) {
        return new ProductSearchRequest(
                buildQuery(input),
                RagSearchFilter.of(
                        "catalog://spu/",
                        null,
                        input.slots() == null ? null : input.slots().categoryHint(),
                        mustNot.tags(),
                        mustNot.brands(),
                        mustNot.ingredients()
                ),
                // 反选场景多召回一些，给 rerank 留余量。
                mustNot.isEmpty() ? effectiveTopK(input.topK()) : RECALL_TOP_K_FOR_NEGATION,
                input.includeChunkTypes() == null ? List.of() : input.includeChunkTypes(),
                input.restrictToSpuRefs() == null ? List.of() : input.restrictToSpuRefs()
        );
    }

    private Slot.MustNot mustNotOf(Slot slot) {
        if (slot == null || slot.mustNot() == null) {
            return Slot.MustNot.empty();
        }
        return slot.mustNot();
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

    /**
     * 用 {@link CatalogQueryFacade} 回填 SPU 的实时 price/stock，避免给 LLM 喂索引快照的陈旧数据。
     * spuId 缺失时降级到 externalRef 再查；都查不到就构造 fallback 卡（refId 仍按位置编号）。
     */
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

    /**
     * 构造 {@code tool.result.facetsApplied}：把 slot 中真正参与过滤的字段输出给客户端，
     * 用于"已为您应用：价格≤300 / 品牌=Sony / 排除=酒精" 这类徽章渲染。
     * 空字段省略，保持客户端 UI 简洁。
     */
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
        if (!slot.mustNot().isEmpty()) {
            Map<String, Object> mn = new LinkedHashMap<>();
            if (!slot.mustNot().tags().isEmpty()) mn.put("tags", slot.mustNot().tags());
            if (!slot.mustNot().brands().isEmpty()) mn.put("brands", slot.mustNot().brands());
            if (!slot.mustNot().ingredients().isEmpty()) mn.put("ingredients", slot.mustNot().ingredients());
            facets.put("mustNot", mn);
        }
        return facets;
    }

    public record SearchProductsInput(
            String query,
            Slot slots,
            Integer topK,
            List<String> includeChunkTypes,
            List<String> restrictToSpuRefs
    ) {
        public SearchProductsInput(String query, Slot slots, Integer topK, List<String> includeChunkTypes) {
            this(query, slots, topK, includeChunkTypes, List.of());
        }
    }

    public record SearchProductsOutput(
            String toolName,
            List<SpuCardView> cards,
            Map<String, Object> facetsApplied,
            List<String> excludedFacets
    ) {
        public SearchProductsOutput {
            cards = cards == null ? List.of() : List.copyOf(cards);
            facetsApplied = facetsApplied == null ? Map.of() : Map.copyOf(facetsApplied);
            excludedFacets = excludedFacets == null ? List.of() : List.copyOf(excludedFacets);
        }

        public SearchProductsOutput(String toolName, List<SpuCardView> cards, Map<String, Object> facetsApplied) {
            this(toolName, cards, facetsApplied, List.of());
        }
    }
}
