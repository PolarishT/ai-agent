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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 商品检索工具。
 *
 * <p>职责边界：
 * <ul>
 *   <li>只检索商品索引，不检索其它知识库；</li>
 *   <li>用 ProductSearchSpi 做召回；</li>
 *   <li>用 NegationRerankFilter 处理反选语义；</li>
 *   <li>用 CatalogQueryFacade 回填实时商品信息；</li>
 *   <li>返回 cards、facetsApplied、excludedFacets 给 AgentTurnService 构造 tool.result。</li>
 * </ul>
 */
@Component
public class SearchProductsToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "search_products";

    private static final String CATALOG_SPU_SOURCE_PREFIX = "catalog://spu/";

    /** 客户端可见 topK 上限；超过该值卡片屏幕渲染压力大，LLM 引用 #N 也容易混乱。 */
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 10;
    private static final int MIN_TOP_K = 1;

    /** 反选场景先多召回，再 rerank 剔假阳。 */
    private static final int RECALL_TOP_K_FOR_NEGATION = 50;

    private static final int MAX_INCLUDE_CHUNK_TYPES = 8;
    private static final int MAX_RESTRICT_TO_SPU_REFS = 50;
    private static final int MAX_REASON_LENGTH = 160;

    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "query":{
                  "type":"string",
                  "description":"用户商品检索查询原文或改写后的检索词，不能为空"
                },
                "slots":{
                  "type":"object",
                  "description":"Agent 已抽取的结构化槽位，例如价格区间、类目、品牌、must、mustNot 等"
                },
                "topK":{
                  "type":"integer",
                  "minimum":1,
                  "maximum":10,
                  "description":"希望返回的商品卡片数量，默认 10，最大 10"
                },
                "includeChunkTypes":{
                  "type":"array",
                  "items":{"type":"string"},
                  "description":"限定检索的 chunk 类型。通常由系统设置，模型不要随意编造"
                },
                "restrictToSpuRefs":{
                  "type":"array",
                  "items":{"type":"string"},
                  "description":"限定只在指定 SPU externalRef 范围内检索，用于上一轮商品集合内继续筛选"
                }
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
                .description("""
                        基于关键词、价格区间、类目、品牌、正向属性和反选属性在电商商品库内检索商品。
                        这是只读检索工具，不会修改购物车或订单。
                        商品卡片中的价格、库存、标题、品牌应以 CatalogQueryFacade 回填的实时信息为准。
                        当用户要求推荐商品、按条件筛选商品、在上一轮结果里继续细化时调用。
                        """)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        SearchProductsInput input = jsonCodec.read(toolInput, SearchProductsInput.class);
        SearchProductsOutput output = search(input);
        return jsonCodec.write(output);
    }

    public SearchProductsOutput search(SearchProductsInput input) {
        validate(input);

        Slot.MustNot mustNot = mustNotOf(input.slots());
        int finalTopK = effectiveTopK(input.topK());

        ProductSearchRequest request = toRequest(input, mustNot, finalTopK);
        List<ProductSearchHit> hits = safeHits(productSearchSpi.search(request));

        List<String> excludedFacets = List.of();

        if (!mustNot.isEmpty() && !hits.isEmpty()) {
            NegationRerankFilter.Result reranked = negationRerankFilter.apply(hits, mustNot);
            hits = safeHits(reranked == null ? null : reranked.keepHits());
            excludedFacets = safeStringList(reranked == null ? null : reranked.excludedFacets());
        }

        hits = deduplicateHits(hits);

        if (hits.size() > finalTopK) {
            hits = hits.subList(0, finalTopK);
        }

        List<SpuCardView> cards = enrichWithCatalog(hits);

        return new SearchProductsOutput(
                TOOL_NAME,
                cards,
                facetsApplied(input.slots(), input, finalTopK, !mustNot.isEmpty()),
                excludedFacets
        );
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(
                IntentType.RECOMMEND_VAGUE,
                IntentType.FILTER_BY_ATTR,
                IntentType.REFINE
        );
    }

    private void validate(SearchProductsInput input) {
        if (input == null) {
            throw new IllegalArgumentException("search_products 输入不能为空");
        }

        if (!StringUtils.hasText(input.query())) {
            throw new IllegalArgumentException("search_products.query 不能为空");
        }
    }

    private ProductSearchRequest toRequest(
            SearchProductsInput input,
            Slot.MustNot mustNot,
            int finalTopK
    ) {
        boolean hasNegation = mustNot != null && !mustNot.isEmpty();

        return new ProductSearchRequest(
                buildQuery(input),
                RagSearchFilter.of(
                        CATALOG_SPU_SOURCE_PREFIX,
                        null,
                        categoryHintOf(input.slots()),
                        mustNot == null ? List.of() : safeStringList(mustNot.tags()),
                        mustNot == null ? List.of() : safeStringList(mustNot.brands()),
                        mustNot == null ? List.of() : safeStringList(mustNot.ingredients())
                ),
                hasNegation ? RECALL_TOP_K_FOR_NEGATION : finalTopK,
                input.includeChunkTypes(),
                input.restrictToSpuRefs()
        );
    }

    private Slot.MustNot mustNotOf(Slot slot) {
        if (slot == null || slot.mustNot() == null) {
            return Slot.MustNot.empty();
        }
        return slot.mustNot();
    }

    private String categoryHintOf(Slot slot) {
        return slot == null ? null : blankToNull(slot.categoryHint());
    }

    private String buildQuery(SearchProductsInput input) {
        String query = input.query().trim();

        List<String> must = input.slots() == null
                ? List.of()
                : safeStringList(input.slots().must());

        if (must.isEmpty()) {
            return query;
        }

        return query + " " + String.join(" ", must);
    }

    private int effectiveTopK(Integer topK) {
        if (topK == null) {
            return DEFAULT_TOP_K;
        }

        if (topK < MIN_TOP_K) {
            return DEFAULT_TOP_K;
        }

        return Math.min(topK, MAX_TOP_K);
    }

    private List<ProductSearchHit> safeHits(List<ProductSearchHit> hits) {
        return hits == null ? List.of() : hits;
    }

    /**
     * 检索索引可能因为 chunk 粒度导致同一个 SPU 多次命中。
     * 对外卡片应按 SPU 去重，否则 LLM 引用 #1/#2 时容易混乱。
     */
    private List<ProductSearchHit> deduplicateHits(List<ProductSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        Map<String, ProductSearchHit> deduped = new LinkedHashMap<>();

        for (ProductSearchHit hit : hits) {
            if (hit == null) {
                continue;
            }

            String key = hitKey(hit);
            if (!StringUtils.hasText(key)) {
                continue;
            }

            deduped.putIfAbsent(key, hit);
        }

        return new ArrayList<>(deduped.values());
    }

    private String hitKey(ProductSearchHit hit) {
        if (hit.spuId() != null) {
            return "spu:" + hit.spuId();
        }

        if (StringUtils.hasText(hit.externalRef())) {
            return "ref:" + hit.externalRef().trim();
        }

        return null;
    }

    /**
     * 用 CatalogQueryFacade 回填 SPU 实时 price/stock，避免使用索引快照里的陈旧数据。
     */
    private List<SpuCardView> enrichWithCatalog(List<ProductSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        List<SpuCardView> cards = new ArrayList<>(hits.size());

        for (ProductSearchHit hit : hits) {
            Optional<CatalogSpuView> spu = resolveSpu(hit);
            String refId = "#" + (cards.size() + 1);

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
            try {
                return catalogQueryFacade.findSpuByExternalRef(hit.externalRef().trim());
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
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
                safeScore(hit == null ? null : hit.score()),
                List.of(),
                reasons(hit),
                refId
        );
    }

    private SpuCardView fallbackCard(ProductSearchHit hit, String refId) {
        return new SpuCardView(
                hit == null ? null : hit.spuId(),
                hit == null ? null : hit.externalRef(),
                hit == null ? null : fallbackTitle(hit),
                null,
                null,
                null,
                null,
                null,
                safeScore(hit == null ? null : hit.score()),
                List.of(),
                reasons(hit),
                refId
        );
    }

    private String fallbackTitle(ProductSearchHit hit) {
        if (hit == null) {
            return null;
        }

        if (StringUtils.hasText(hit.externalRef())) {
            return hit.externalRef().trim();
        }

        return hit.spuId() == null ? "未知商品" : "SPU-" + hit.spuId();
    }

    private List<String> reasons(ProductSearchHit hit) {
        if (hit == null || !StringUtils.hasText(hit.snippet())) {
            return List.of();
        }

        return List.of(truncate(hit.snippet().trim(), MAX_REASON_LENGTH));
    }

    private String firstImage(List<String> images) {
        return images == null || images.isEmpty() ? null : images.get(0);
    }

    /**
     * 构造 tool.result.facetsApplied。
     * 只输出真正参与过滤或上下文限制的字段，方便客户端渲染“已应用条件”徽章。
     */
    private Map<String, Object> facetsApplied(
            Slot slot,
            SearchProductsInput input,
            int finalTopK,
            boolean usedNegationRerank
    ) {
        Map<String, Object> facets = new LinkedHashMap<>();
        facets.put("action", "search");
        facets.put("topK", finalTopK);

        if (usedNegationRerank) {
            facets.put("negationRerank", true);
        }

        if (input != null && !input.restrictToSpuRefs().isEmpty()) {
            facets.put("restrictToSpuRefs", input.restrictToSpuRefs());
        }

        if (slot == null || slot.isEmpty()) {
            return facets;
        }

        if (slot.priceRange() != null && !slot.priceRange().isEmpty()) {
            facets.put("priceRange", slot.priceRange());
        }

        if (StringUtils.hasText(slot.categoryHint())) {
            facets.put("categoryHint", slot.categoryHint().trim());
        }

        List<String> brands = safeStringList(slot.brands());
        if (!brands.isEmpty()) {
            facets.put("brands", brands);
        }

        List<String> must = safeStringList(slot.must());
        if (!must.isEmpty()) {
            facets.put("must", must);
        }

        Slot.MustNot mustNot = mustNotOf(slot);
        if (!mustNot.isEmpty()) {
            Map<String, Object> mn = new LinkedHashMap<>();

            List<String> tags = safeStringList(mustNot.tags());
            if (!tags.isEmpty()) {
                mn.put("tags", tags);
            }

            List<String> brandsNot = safeStringList(mustNot.brands());
            if (!brandsNot.isEmpty()) {
                mn.put("brands", brandsNot);
            }

            List<String> ingredients = safeStringList(mustNot.ingredients());
            if (!ingredients.isEmpty()) {
                mn.put("ingredients", ingredients);
            }

            if (!mn.isEmpty()) {
                facets.put("mustNot", mn);
            }
        }

        return facets;
    }

    private double safeScore(Double score) {
        if (score == null || score.isNaN() || score.isInfinite()) {
            return 0.0d;
        }

        return Math.max(0.0d, score);
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private static List<String> safeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (String value : values) {
            if (StringUtils.hasText(value)) {
                result.add(value.trim());
            }
        }

        return List.copyOf(result);
    }

    private static List<String> normalizeStringList(List<String> values, int maxSize) {
        List<String> normalized = safeStringList(values);

        if (normalized.size() <= maxSize) {
            return normalized;
        }

        return normalized.subList(0, maxSize);
    }

    public record SearchProductsInput(
            String query,
            Slot slots,
            Integer topK,
            List<String> includeChunkTypes,
            List<String> restrictToSpuRefs
    ) {
        public SearchProductsInput {
            query = query == null ? null : query.trim();
            includeChunkTypes = normalizeStringList(includeChunkTypes, MAX_INCLUDE_CHUNK_TYPES);
            restrictToSpuRefs = normalizeStringList(restrictToSpuRefs, MAX_RESTRICT_TO_SPU_REFS);
        }

        public SearchProductsInput(
                String query,
                Slot slots,
                Integer topK,
                List<String> includeChunkTypes
        ) {
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

        public SearchProductsOutput(
                String toolName,
                List<SpuCardView> cards,
                Map<String, Object> facetsApplied
        ) {
            this(toolName, cards, facetsApplied, List.of());
        }
    }
}