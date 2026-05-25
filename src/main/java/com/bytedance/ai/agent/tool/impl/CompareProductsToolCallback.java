package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.infrastructure.config.RagConcurrencyConfiguration;
import com.bytedance.ai.retrieval.spi.ProductSearchHit;
import com.bytedance.ai.retrieval.spi.ProductSearchRequest;
import com.bytedance.ai.retrieval.spi.ProductSearchSpi;
import com.bytedance.ai.shared.metadata.RagSearchFilter;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CompareProductsToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "compare_products";
    private static final int DEFAULT_TOP_K = 3;
    private static final int MIN_COMPARE_PRODUCTS = 2;
    private static final int MAX_COMPARE_PRODUCTS = 5;
    private static final Pattern EXTERNAL_REF = Pattern.compile("\\bSPU[-_A-Za-z0-9]+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_ID = Pattern.compile("(?<![A-Za-z0-9_-])\\d{1,12}(?![A-Za-z0-9_-])");
    private static final Pattern TWO_PRODUCTS = Pattern.compile("(两款|两个|两件|两种|2款|2个|2件|2种)");
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "query":{"type":"string","description":"用户对比请求"},
                "spuIds":{"type":"array","items":{"type":"integer"}},
                "externalRefs":{"type":"array","items":{"type":"string"}},
                "topK":{"type":"integer","minimum":2,"maximum":5},
                "compareAspects":{"type":"array","items":{"type":"string"}}
              },
              "required":["query"]
            }
            """;

    private final ProductSearchSpi productSearchSpi;
    private final CatalogQueryFacade catalogQueryFacade;
    private final RagJsonCodec jsonCodec;
    private final Scheduler ragBlockingScheduler;

    public CompareProductsToolCallback(
            ProductSearchSpi productSearchSpi,
            CatalogQueryFacade catalogQueryFacade,
            RagJsonCodec jsonCodec,
            @Qualifier(RagConcurrencyConfiguration.RAG_BLOCKING_SCHEDULER) Scheduler ragBlockingScheduler
    ) {
        this.productSearchSpi = productSearchSpi;
        this.catalogQueryFacade = catalogQueryFacade;
        this.jsonCodec = jsonCodec;
        this.ragBlockingScheduler = ragBlockingScheduler;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("对比 2 到 5 个商品，输出属性矩阵与推荐理由")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        CompareProductsInput input = jsonCodec.read(toolInput, CompareProductsInput.class);
        return jsonCodec.write(compare(input));
    }

    public CompareProductsOutput compare(CompareProductsInput input) {
        if (input == null || !StringUtils.hasText(input.query())) {
            throw new IllegalArgumentException("compare_products.query 不能为空");
        }
        List<String> aspects = normalizeAspects(input.query(), input.compareAspects());
        List<ResolvedCandidate> candidates = resolveCandidates(input);
        List<SpuCardView> cards = toCards(candidates);
        CompareMatrixView matrix = cards.size() < 2 ? null : buildMatrix(cards, candidates, aspects);
        return new CompareProductsOutput(TOOL_NAME, cards, matrix);
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.COMPARE);
    }

    private List<ResolvedCandidate> resolveCandidates(CompareProductsInput input) {
        LinkedHashMap<String, ResolvedCandidate> resolved = new LinkedHashMap<>();
        int targetCount = targetCount(input);
        for (Long spuId : safeList(input.spuIds())) {
            resolveBySpuId(spuId).ifPresent(candidate -> putCandidate(resolved, candidate));
        }
        for (String externalRef : safeList(input.externalRefs())) {
            resolveByExternalRef(externalRef).ifPresent(candidate -> putCandidate(resolved, candidate));
        }
        for (QueryReference reference : parseQueryReferences(input.query())) {
            if (reference.spuId() != null) {
                resolveBySpuId(reference.spuId()).ifPresent(candidate -> putCandidate(resolved, candidate));
            } else {
                resolveByExternalRef(reference.externalRef()).ifPresent(candidate -> putCandidate(resolved, candidate));
            }
        }
        if (resolved.size() < targetCount) {
            List<String> tokens = parseTextCandidates(input.query());
            List<ResolvedCandidate> textResolved = Flux.fromIterable(tokens)
                    .filter(StringUtils::hasText)
                    .filter(token -> !looksLikeIgnoredToken(token))
                    .flatMap(token -> Flux.defer(() -> Flux.fromIterable(resolveByText(token, 1)))
                            .subscribeOn(ragBlockingScheduler))
                    .collectList()
                    .blockOptional()
                    .orElse(List.of());
            for (ResolvedCandidate candidate : textResolved) {
                putCandidate(resolved, candidate);
                if (resolved.size() >= targetCount) {
                    break;
                }
            }
        }
        if (resolved.size() < MIN_COMPARE_PRODUCTS) {
            for (ResolvedCandidate candidate : resolveByText(normalizeBroadSearchQuery(input.query()), targetCount)) {
                putCandidate(resolved, candidate);
                if (resolved.size() >= targetCount) {
                    break;
                }
            }
        }
        return resolved.values().stream()
                .limit(MAX_COMPARE_PRODUCTS)
                .toList();
    }

    private List<QueryReference> parseQueryReferences(String query) {
        List<QueryReference> references = new ArrayList<>();
        Matcher matcher = EXTERNAL_REF.matcher(query == null ? "" : query);
        while (matcher.find()) {
            references.add(new QueryReference(null, matcher.group().trim(), matcher.start()));
        }
        matcher = NUMERIC_ID.matcher(query == null ? "" : query);
        while (matcher.find()) {
            references.add(new QueryReference(Long.valueOf(matcher.group()), null, matcher.start()));
        }
        return references.stream()
                .sorted((left, right) -> Integer.compare(left.position(), right.position()))
                .toList();
    }

    private List<String> parseTextCandidates(String query) {
        String normalized = query == null ? "" : query;
        normalized = EXTERNAL_REF.matcher(normalized).replaceAll(" ");
        normalized = NUMERIC_ID.matcher(normalized).replaceAll(" ");
        normalized = normalized.replaceAll("(?i)\\bvs\\b", " ");
        normalized = normalized.replaceAll("(对比|比较|哪个好|哪个更|哪款|这两款|这两个|这件|这个|性价比|保湿|更|哪个|哪一个)", " ");
        normalized = normalized.replaceAll("[,，、/和与]|\\s+", " ");
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.trim().split(" ")) {
            if (StringUtils.hasText(token)) {
                tokens.add(token.trim());
            }
        }
        return tokens;
    }

    private String normalizeBroadSearchQuery(String query) {
        String normalized = query == null ? "" : query;
        normalized = EXTERNAL_REF.matcher(normalized).replaceAll(" ");
        normalized = NUMERIC_ID.matcher(normalized).replaceAll(" ");
        normalized = normalized.replaceAll("(?i)\\bvs\\b", " ");
        normalized = normalized.replaceAll("(对比|比较|哪个好|哪个更|哪款|这两款|这两个|这件|这个|哪个|哪一个|的)", " ");
        normalized = normalized.replaceAll("(保湿|性价比|价格|功效|成分|肤感|适合|推荐)", " ");
        normalized = normalized.replaceAll("[,，、/和与]|\\s+", " ").trim();
        return StringUtils.hasText(normalized) ? normalized : query;
    }

    private Optional<ResolvedCandidate> resolveBySpuId(Long spuId) {
        if (spuId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ResolvedCandidate(catalogQueryFacade.getSpu(spuId), 1.0d, "用户指定商品"));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<ResolvedCandidate> resolveByExternalRef(String externalRef) {
        if (!StringUtils.hasText(externalRef)) {
            return Optional.empty();
        }
        return catalogQueryFacade.findSpuByExternalRef(externalRef.trim())
                .map(spu -> new ResolvedCandidate(spu, 1.0d, "用户指定商品"));
    }

    private List<ResolvedCandidate> resolveByText(String token, int topK) {
        if (!StringUtils.hasText(token)) {
            return List.of();
        }
        List<ProductSearchHit> hits = productSearchSpi.search(new ProductSearchRequest(
                token,
                RagSearchFilter.of("catalog://spu/", null, null),
                Math.max(1, Math.min(topK, MAX_COMPARE_PRODUCTS)),
                List.of()
        ));
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<ResolvedCandidate> candidates = new ArrayList<>();
        for (ProductSearchHit hit : hits) {
            resolveSpu(hit).ifPresent(spu -> candidates.add(new ResolvedCandidate(spu, hit.score(), hit.snippet())));
        }
        return candidates;
    }

    private Optional<CatalogSpuView> resolveSpu(ProductSearchHit hit) {
        if (hit == null) {
            return Optional.empty();
        }
        if (hit.spuId() != null) {
            try {
                return Optional.of(catalogQueryFacade.getSpu(hit.spuId()));
            } catch (IllegalArgumentException ignored) {
                // 检索索引可能落后，继续尝试 externalRef。
            }
        }
        if (StringUtils.hasText(hit.externalRef())) {
            return catalogQueryFacade.findSpuByExternalRef(hit.externalRef());
        }
        return Optional.empty();
    }

    private void putCandidate(Map<String, ResolvedCandidate> resolved, ResolvedCandidate candidate) {
        String key = candidate.spu().externalRef();
        if (!StringUtils.hasText(key)) {
            key = String.valueOf(candidate.spu().id());
        }
        resolved.putIfAbsent(key, candidate);
    }

    private List<SpuCardView> toCards(List<ResolvedCandidate> candidates) {
        List<SpuCardView> cards = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            ResolvedCandidate candidate = candidates.get(i);
            CatalogSpuView spu = candidate.spu();
            cards.add(new SpuCardView(
                    spu.id(),
                    spu.externalRef(),
                    spu.title(),
                    spu.brand(),
                    firstImage(spu.images()),
                    spu.priceMin(),
                    spu.priceMax(),
                    spu.stock(),
                    candidate.score(),
                    List.of(),
                    StringUtils.hasText(candidate.reason()) ? List.of(candidate.reason()) : List.of(),
                    "#" + (i + 1)
            ));
        }
        return cards;
    }

    private CompareMatrixView buildMatrix(List<SpuCardView> cards, List<ResolvedCandidate> candidates, List<String> aspects) {
        List<CompareMatrixView.ProductColumn> products = cards.stream()
                .map(card -> new CompareMatrixView.ProductColumn(card.refId(), card.spuId(), card.externalRef(), card.title()))
                .toList();
        List<CompareMatrixView.AttributeRow> rows = new ArrayList<>();
        rows.add(row("品牌", cards.stream().map(card -> valueOrUnknown(card.brand())).toList()));
        rows.add(row("价格", cards.stream().map(this::priceText).toList()));
        rows.add(row("库存", cards.stream().map(card -> card.stock() == null ? "未知" : card.stock() + " 件").toList()));
        rows.add(row("匹配理由", cards.stream()
                .map(card -> card.reasons().isEmpty() ? "暂无明确匹配理由" : card.reasons().getFirst())
                .toList()));
        for (String aspect : aspects) {
            rows.add(row(aspect, candidates.stream().map(candidate -> aspectValue(candidate.spu(), aspect)).toList()));
        }
        SpuCardView recommended = recommend(cards, aspects);
        String reason = recommendationReason(recommended, aspects);
        return new CompareMatrixView(products, rows, recommended == null ? null : recommended.refId(), reason);
    }

    private CompareMatrixView.AttributeRow row(String attribute, List<String> values) {
        return new CompareMatrixView.AttributeRow(attribute, values);
    }

    private SpuCardView recommend(List<SpuCardView> cards, List<String> aspects) {
        if (cards.isEmpty()) {
            return null;
        }
        boolean valueForMoney = aspects.stream().anyMatch(aspect -> aspect.contains("性价比"));
        if (valueForMoney) {
            return cards.stream()
                    .filter(card -> card.priceMin() != null)
                    .min((left, right) -> left.priceMin().compareTo(right.priceMin()))
                    .orElse(cards.getFirst());
        }
        return cards.stream()
                .max((left, right) -> Double.compare(left.score() == null ? 0.0d : left.score(), right.score() == null ? 0.0d : right.score()))
                .orElse(cards.getFirst());
    }

    private String recommendationReason(SpuCardView recommended, List<String> aspects) {
        if (recommended == null) {
            return null;
        }
        if (aspects.stream().anyMatch(aspect -> aspect.contains("性价比"))) {
            return recommended.refId() + " 价格更友好，适合作为性价比优先选择。";
        }
        if (aspects.isEmpty()) {
            return recommended.refId() + " 综合匹配度更高。";
        }
        return recommended.refId() + " 更贴合关注点：" + String.join("、", aspects) + "。";
    }

    private List<String> normalizeAspects(String query, List<String> inputAspects) {
        LinkedHashSet<String> aspects = new LinkedHashSet<>();
        for (String aspect : safeList(inputAspects)) {
            if (StringUtils.hasText(aspect)) {
                aspects.add(aspect.trim());
            }
        }
        if (contains(query, "保湿")) {
            aspects.add("保湿");
        }
        if (contains(query, "性价比")) {
            aspects.add("性价比");
        }
        return List.copyOf(aspects);
    }

    private String aspectValue(CatalogSpuView spu, String aspect) {
        if (spu.attributes() != null) {
            for (Map.Entry<String, Object> entry : spu.attributes().entrySet()) {
                if (entry.getKey() != null && entry.getKey().contains(aspect) && entry.getValue() != null) {
                    return String.valueOf(entry.getValue());
                }
            }
        }
        String haystack = (spu.title() == null ? "" : spu.title()) + " " + (spu.descriptionMd() == null ? "" : spu.descriptionMd());
        if (haystack.contains(aspect)) {
            return "候选信息提到" + aspect;
        }
        if ("性价比".equals(aspect)) {
            return priceText(spu.priceMin(), spu.priceMax()) + "，库存 " + (spu.stock() == null ? "未知" : spu.stock() + " 件");
        }
        return "暂无明确字段";
    }

    private int targetCount(CompareProductsInput input) {
        if (input == null) {
            return DEFAULT_TOP_K;
        }
        if (mentionsTwoProducts(input.query())) {
            return MIN_COMPARE_PRODUCTS;
        }
        if (input.topK() == null || input.topK() <= 0 || input.topK() > MAX_COMPARE_PRODUCTS) {
            return DEFAULT_TOP_K;
        }
        return Math.min(input.topK(), MAX_COMPARE_PRODUCTS);
    }

    private boolean mentionsTwoProducts(String query) {
        return query != null && TWO_PRODUCTS.matcher(query).find();
    }

    private boolean looksLikeIgnoredToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.length() <= 1
                || lower.equals("a")
                || lower.equals("b")
                || lower.equals("c")
                || lower.contains("这两款")
                || lower.contains("这两个")
                || lower.endsWith("的");
    }

    private String priceText(SpuCardView card) {
        return priceText(card.priceMin(), card.priceMax());
    }

    private String priceText(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return "未知";
        }
        if (min == null) {
            return "¥" + max;
        }
        if (max == null || min.compareTo(max) == 0) {
            return "¥" + min;
        }
        return "¥" + min + "-¥" + max;
    }

    private boolean contains(String text, String token) {
        return text != null && text.contains(token);
    }

    private String firstImage(List<String> images) {
        return images == null || images.isEmpty() ? null : images.getFirst();
    }

    private String valueOrUnknown(String value) {
        return StringUtils.hasText(value) ? value : "未知";
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record CompareProductsInput(
            String query,
            List<Long> spuIds,
            List<String> externalRefs,
            Integer topK,
            List<String> compareAspects
    ) {
        public CompareProductsInput {
            spuIds = spuIds == null ? List.of() : List.copyOf(spuIds);
            externalRefs = externalRefs == null ? List.of() : List.copyOf(externalRefs);
            compareAspects = compareAspects == null ? List.of() : List.copyOf(compareAspects);
        }
    }

    public record CompareProductsOutput(
            String toolName,
            List<SpuCardView> cards,
            CompareMatrixView compareMatrix
    ) {
        public CompareProductsOutput {
            cards = cards == null ? List.of() : List.copyOf(cards);
        }
    }

    private record ResolvedCandidate(CatalogSpuView spu, Double score, String reason) {
    }

    private record QueryReference(Long spuId, String externalRef, int position) {
    }
}
