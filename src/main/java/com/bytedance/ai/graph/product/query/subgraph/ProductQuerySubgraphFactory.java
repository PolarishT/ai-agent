package com.bytedance.ai.graph.product.query.subgraph;

import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.bytedance.ai.graph.GuideGraphStateKeys;
import com.bytedance.ai.graph.conversation.ConversationMessage;
import com.bytedance.ai.graph.intent.MainIntent;
import com.bytedance.ai.graph.product.query.persistence.PendingProductQueryAction;
import com.bytedance.ai.graph.product.query.persistence.PendingProductQueryRepository;
import com.bytedance.ai.graph.product.query.persistence.PendingProductQueryStatus;
import com.bytedance.ai.graph.product.query.ProductComparisonResult;
import com.bytedance.ai.graph.product.query.ProductHydrationOptions;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductQueryGraphStateKeys;
import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import com.bytedance.ai.graph.product.query.service.ProductCandidatePostFilter;
import com.bytedance.ai.graph.product.query.service.ProductComparisonBuilder;
import com.bytedance.ai.graph.product.query.service.ProductHydrationOptionsResolver;
import com.bytedance.ai.graph.product.query.service.ProductQueryDebugLogger;
import com.bytedance.ai.graph.product.query.service.ProductQueryConditionLlmService;
import com.bytedance.ai.graph.product.query.service.ProductQueryConditionMerger;
import com.bytedance.ai.graph.product.query.service.ProductQueryConditionValidator;
import com.bytedance.ai.graph.product.query.service.ProductQueryResponseBuilder;
import com.bytedance.ai.graph.product.query.service.ProductRanker;
import com.bytedance.ai.graph.product.query.service.ProductSearchFilterBuilder;
import com.bytedance.ai.graph.product.retrieval.ProductHardFilter;
import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.graph.product.retrieval.ProductSearchRequest;
import com.bytedance.ai.graph.product.retrieval.ProductSearchResult;
import com.bytedance.ai.graph.product.retrieval.ProductSearchSpi;
import com.bytedance.ai.shared.properties.RagProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 商品查询子图工厂：构造 product_query_workflow 的 11 节点 StateGraph。
 *
 * <p>注意：这是只读链路（{@code writeAction=false}）。不会修改 cart / order 状态，
 * 仅向 {@code pending_product_query_actions} 持久化当前轮 condition + 排序后候选，
 * 供下一轮 INHERIT/OVERRIDE/APPEND 与跨 workflow（cartmanage 后续读）使用。
 */
@Component
public class ProductQuerySubgraphFactory {

    private static final Logger log = LoggerFactory.getLogger(ProductQuerySubgraphFactory.class);

    static final String SUBGRAPH_NAME = "product_query_subgraph";
    static final String STATUS_OK = "SUCCESS";
    static final String STATUS_CLARIFY = "WAITING_CLARIFICATION";
    static final String STATUS_NO_HITS = "NO_HITS";
    static final String STATUS_FAILED = "FAILED";

    private final ProductQueryConditionLlmService llmService;
    private final ProductQueryConditionValidator validator;
    private final ProductQueryConditionMerger merger;
    private final ProductSearchFilterBuilder filterBuilder;
    private final ProductSearchSpi productSearchSpi;
    private final ProductRanker productRanker;
    private final ProductCandidatePostFilter postFilter;
    private final ProductHydrationOptionsResolver hydrationOptionsResolver;
    private final ProductQueryDebugLogger debugLogger;
    private final ProductComparisonBuilder comparisonBuilder;
    private final ProductQueryResponseBuilder responseBuilder;
    private final PendingProductQueryRepository pendingRepository;
    private final RagProperties ragProperties;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public ProductQuerySubgraphFactory(
            ProductQueryConditionLlmService llmService,
            ProductQueryConditionValidator validator,
            ProductQueryConditionMerger merger,
            ProductSearchFilterBuilder filterBuilder,
            ProductSearchSpi productSearchSpi,
            ProductRanker productRanker,
            ProductCandidatePostFilter postFilter,
            ProductHydrationOptionsResolver hydrationOptionsResolver,
            ProductQueryDebugLogger debugLogger,
            ProductComparisonBuilder comparisonBuilder,
            ProductQueryResponseBuilder responseBuilder,
            PendingProductQueryRepository pendingRepository,
            RagProperties ragProperties,
            ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        this.llmService = llmService;
        this.validator = validator;
        this.merger = merger;
        this.filterBuilder = filterBuilder;
        this.productSearchSpi = productSearchSpi;
        this.productRanker = productRanker;
        this.postFilter = postFilter;
        this.hydrationOptionsResolver = hydrationOptionsResolver;
        this.debugLogger = debugLogger;
        this.comparisonBuilder = comparisonBuilder;
        this.responseBuilder = responseBuilder;
        this.pendingRepository = pendingRepository;
        this.ragProperties = ragProperties;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    public StateGraph build() {
        try {
            return buildInternal();
        } catch (com.alibaba.cloud.ai.graph.exception.GraphStateException ex) {
            throw new IllegalStateException("product_query_subgraph compile failed", ex);
        }
    }

    private StateGraph buildInternal() throws com.alibaba.cloud.ai.graph.exception.GraphStateException {
        StateGraph subgraph = new StateGraph(SUBGRAPH_NAME, keyStrategyFactory());

        subgraph.addNode("pq_load_context", AsyncNodeAction.node_async(this::pqLoadContext));
        subgraph.addNode("pq_transform_query", AsyncNodeAction.node_async(this::pqTransformQuery));
        subgraph.addNode("pq_validate_condition", AsyncNodeAction.node_async(this::pqValidateCondition));
        subgraph.addNode("pq_build_filter", AsyncNodeAction.node_async(this::pqBuildFilter));
        subgraph.addNode("pq_keyword_retrieve", AsyncNodeAction.node_async(this::pqKeywordRetrieve));
        subgraph.addNode("pq_semantic_retrieve", AsyncNodeAction.node_async(this::pqSemanticRetrieve));
        subgraph.addNode("pq_fusion", AsyncNodeAction.node_async(this::pqFusion));
        subgraph.addNode("pq_rank", AsyncNodeAction.node_async(this::pqRank));
        subgraph.addNode("pq_build_comparison", AsyncNodeAction.node_async(this::pqBuildComparison));
        subgraph.addNode("pq_build_result_list", AsyncNodeAction.node_async(this::pqBuildResultList));
        subgraph.addNode("pq_final_response", AsyncNodeAction.node_async(this::pqFinalResponse));

        subgraph.addEdge(StateGraph.START, "pq_load_context");
        subgraph.addEdge("pq_load_context", "pq_transform_query");
        subgraph.addEdge("pq_transform_query", "pq_validate_condition");
        subgraph.addConditionalEdges(
                "pq_validate_condition",
                AsyncEdgeAction.edge_async(this::routeAfterValidate),
                Map.of(
                        "CLARIFY", "pq_final_response",
                        "OK", "pq_build_filter"
                )
        );
        subgraph.addEdge("pq_build_filter", "pq_keyword_retrieve");
        subgraph.addEdge("pq_keyword_retrieve", "pq_semantic_retrieve");
        subgraph.addEdge("pq_semantic_retrieve", "pq_fusion");
        subgraph.addEdge("pq_fusion", "pq_rank");
        subgraph.addConditionalEdges(
                "pq_rank",
                AsyncEdgeAction.edge_async(this::routeAfterRank),
                Map.of(
                        "COMPARE", "pq_build_comparison",
                        "LIST", "pq_build_result_list"
                )
        );
        subgraph.addEdge("pq_build_comparison", "pq_final_response");
        subgraph.addEdge("pq_build_result_list", "pq_final_response");
        subgraph.addEdge("pq_final_response", StateGraph.END);
        return subgraph;
    }

    private KeyStrategyFactory keyStrategyFactory() {
        return new KeyStrategyFactoryBuilder()
                .defaultStrategy(new ReplaceStrategy())
                .build();
    }

    // -------- 节点实现 --------

    private Map<String, Object> pqLoadContext(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");

        Map<String, Object> updates = new LinkedHashMap<>();
        clearTransientState(updates);
        Optional<PendingProductQueryAction> previous = pendingRepository
                .findActiveByUserIdAndConversationId(userId, conversationId);
        previous.ifPresent(record -> {
            updates.put(ProductQueryGraphStateKeys.LAST_PRODUCT_QUERY_CONTEXT, record);
            updates.put(ProductQueryGraphStateKeys.PENDING_PRODUCT_QUERY_ID, record.id());
        });
        log.info("Product query load context done: userId={}, conversationId={}, hasPrevious={}, messageLen={}",
                userId, conversationId, previous.isPresent(),
                userMessage == null ? 0 : userMessage.length());
        return updates;
    }

    private Map<String, Object> pqTransformQuery(OverAllState state) {
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");
        String memory = conversationMemory(state);
        ProductQueryCondition previousCondition = previousCondition(state);
        String summary = ProductQueryConditionLlmService.summarizePrevious(previousCondition);
        ProductQueryCondition parsed = llmService.parse(userMessage, memory, summary);
        log.info("Product query transform done: intent={}, refineType={}, needComparison={}, confidence={}",
                parsed.intent(), parsed.refineType(), parsed.needComparison(), parsed.confidence());
        return Map.of(ProductQueryGraphStateKeys.PRODUCT_QUERY_CONDITION, parsed);
    }

    private Map<String, Object> pqValidateCondition(OverAllState state) {
        ProductQueryCondition raw = state.value(
                ProductQueryGraphStateKeys.PRODUCT_QUERY_CONDITION, ProductQueryCondition.class
        ).orElseGet(() -> ProductQueryCondition.empty(state.value(GuideGraphStateKeys.MESSAGE, "")));

        ProductQueryCondition validated = validator.validate(raw);
        ProductQueryCondition merged = merger.merge(validated, previousCondition(state));
        ProductQueryCondition finalCondition = validator.validate(merged); // re-validate after merge

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(ProductQueryGraphStateKeys.PRODUCT_QUERY_CONDITION, finalCondition);
        if (finalCondition.needClarify()) {
            updates.put(ProductQueryGraphStateKeys.WORKFLOW_STATUS, STATUS_CLARIFY);
            updates.put(ProductQueryGraphStateKeys.NEED_USER_INPUT, true);
            updates.put(ProductQueryGraphStateKeys.NODE_MESSAGE,
                    responseBuilder.buildClarifyResponse(finalCondition));
        }
        return updates;
    }

    private String routeAfterValidate(OverAllState state) {
        ProductQueryCondition condition = state.value(
                ProductQueryGraphStateKeys.PRODUCT_QUERY_CONDITION, ProductQueryCondition.class
        ).orElse(null);
        return condition != null && condition.needClarify() ? "CLARIFY" : "OK";
    }

    private Map<String, Object> pqBuildFilter(OverAllState state) {
        ProductQueryCondition condition = requireCondition(state);
        ProductHardFilter filter = filterBuilder.build(condition);
        ProductQueryIntent intent = resolveProductQueryIntent(state, condition);
        ProductHydrationOptions hydrationOptions = hydrationOptionsResolver.optionsFor(intent);
        log.info(
                "Product query build filter done: priceMin={}, priceMax={}, brands={}, categories={}, excludeColors={}, mustHaveStock={}, intent={}, hydration={}",
                filter.priceMin(), filter.priceMax(),
                filter.brands().size(), filter.categories().size(),
                filter.excludeColors().size(), filter.mustHaveStock(),
                intent, hydrationOptions
        );
        // 调试观测：slot / PG hard-filter / Milvus filter / 意图决定的 hydrate 范围
        debugLogger.logSlots(condition);
        debugLogger.logFilters(condition, intent);
        debugLogger.logIntent(intent, hydrationOptions);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(ProductQueryGraphStateKeys.PRODUCT_SEARCH_FILTER, filter);
        updates.put(ProductQueryGraphStateKeys.PRODUCT_QUERY_INTENT, intent.name());
        updates.put(ProductQueryGraphStateKeys.PRODUCT_HYDRATION_OPTIONS, hydrationOptions);
        return updates;
    }

    private ProductQueryIntent resolveProductQueryIntent(OverAllState state, ProductQueryCondition condition) {
        String mainIntentRaw = state.value(GuideGraphStateKeys.MAIN_INTENT, "");
        MainIntent mainIntent = null;
        if (StringUtils.hasText(mainIntentRaw)) {
            try {
                mainIntent = MainIntent.valueOf(mainIntentRaw);
            } catch (IllegalArgumentException ignored) {
                mainIntent = null;
            }
        }
        return ProductQueryIntent.from(mainIntent, condition == null ? null : condition.intent());
    }

    /**
     * 先用 {@code pq_build_filter} 写入 state 的 {@link ProductQueryGraphStateKeys#PRODUCT_QUERY_INTENT}，
     * 取不到时回落到原始 MainIntent / condition.intent 重新推导。
     */
    private ProductQueryIntent resolveIntentFromState(OverAllState state, ProductQueryCondition condition) {
        String raw = state.value(ProductQueryGraphStateKeys.PRODUCT_QUERY_INTENT, "");
        if (StringUtils.hasText(raw)) {
            try {
                return ProductQueryIntent.valueOf(raw);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return resolveProductQueryIntent(state, condition);
    }

    private Map<String, Object> pqKeywordRetrieve(OverAllState state) {
        ProductQueryCondition condition = requireCondition(state);
        ProductHardFilter filter = state.value(
                ProductQueryGraphStateKeys.PRODUCT_SEARCH_FILTER, ProductHardFilter.class
        ).orElseGet(ProductHardFilter::empty);
        int topK = ragProperties.productQuery().defaultTopK();
        // 语义分支 embedding 用原始口语 query，保留语气词 / 否定意图；
        // keyword 分支允许 LLM 给出去口语化版本以减少噪声。
        String keywordQuery = pickQuery(condition.keywordQuery(), condition.normalizedQuery(), condition.rawQuery());
        String semanticQuery = pickQuery(condition.rawQuery(), condition.semanticQuery(), condition.normalizedQuery());
        ProductQueryIntent intent = resolveIntentFromState(state, condition);
        ProductSearchRequest request = new ProductSearchRequest(
                keywordQuery,
                topK,
                filter,
                semanticQuery,
                condition,
                intent
        );
        ProductSearchResult result = productSearchSpi.searchProduct(request);
        log.info("Product query SPI done: keyword={}, semantic={}, fused={}, ranked={}, degraded={}",
                result.keywordHits().size(),
                result.semanticHits().size(),
                result.fusedHits().size(),
                result.rankedHits().size(),
                result.degradedNotes());
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(ProductQueryGraphStateKeys.PRODUCT_SEARCH_RESULT, result);
        updates.put(ProductQueryGraphStateKeys.KEYWORD_HITS, result.keywordHits());
        updates.put(ProductQueryGraphStateKeys.DEGRADED_NOTES, result.degradedNotes());
        return updates;
    }

    private Map<String, Object> pqSemanticRetrieve(OverAllState state) {
        ProductSearchResult result = state.value(
                ProductQueryGraphStateKeys.PRODUCT_SEARCH_RESULT, ProductSearchResult.class
        ).orElseGet(ProductSearchResult::empty);
        if (!result.degradedNotes().isEmpty()) {
            recordDegraded(result.degradedNotes());
        }
        return Map.of(ProductQueryGraphStateKeys.SEMANTIC_HITS, result.semanticHits());
    }

    private Map<String, Object> pqFusion(OverAllState state) {
        ProductSearchResult result = state.value(
                ProductQueryGraphStateKeys.PRODUCT_SEARCH_RESULT, ProductSearchResult.class
        ).orElseGet(ProductSearchResult::empty);
        return Map.of(ProductQueryGraphStateKeys.FUSED_HITS, result.fusedHits());
    }

    private Map<String, Object> pqRank(OverAllState state) {
        ProductQueryCondition condition = requireCondition(state);
        ProductSearchResult result = state.value(
                ProductQueryGraphStateKeys.PRODUCT_SEARCH_RESULT, ProductSearchResult.class
        ).orElseGet(ProductSearchResult::empty);
        List<ProductSearchHit> ranked = result.rankedHits();
        List<ProductSearchCandidate> refined = productRanker.refine(
                ranked,
                result.keywordHits(),
                result.semanticHits(),
                condition
        );

        ProductCandidatePostFilter.Result postFilterResult = postFilter.filter(refined, condition);
        log.info(
                "product post filter completed: hydratedCount={}, removedCount={}, remainingCount={}, reasons={}",
                refined.size(),
                postFilterResult.removed().size(),
                postFilterResult.kept().size(),
                postFilterResult.removalReasons()
        );
        debugLogger.logPostFilter(refined.size(), postFilterResult);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(ProductQueryGraphStateKeys.RANKED_PRODUCTS, postFilterResult.kept());
        updates.put(ProductQueryGraphStateKeys.POST_FILTERED_PRODUCTS, postFilterResult.kept());
        updates.put(ProductQueryGraphStateKeys.POST_FILTER_REASONS, postFilterResult.removalReasons());
        return updates;
    }

    private String routeAfterRank(OverAllState state) {
        ProductQueryCondition condition = state.value(
                ProductQueryGraphStateKeys.PRODUCT_QUERY_CONDITION, ProductQueryCondition.class
        ).orElse(null);
        @SuppressWarnings("unchecked")
        List<ProductSearchCandidate> ranked = (List<ProductSearchCandidate>) state
                .value(ProductQueryGraphStateKeys.RANKED_PRODUCTS, List.class)
                .orElse(List.of());
        if (condition == null) {
            return "LIST";
        }
        int defaultTopN = ragProperties.productQuery().comparison().defaultTopN();
        boolean comparableByDefault = condition.needComparison() && ranked.size() >= defaultTopN;
        boolean comparableByTargets = condition.needComparison()
                && !condition.comparisonTargets().isEmpty()
                && !ranked.isEmpty();
        return comparableByDefault || comparableByTargets ? "COMPARE" : "LIST";
    }

    private Map<String, Object> pqBuildComparison(OverAllState state) {
        ProductQueryCondition condition = requireCondition(state);
        @SuppressWarnings("unchecked")
        List<ProductSearchCandidate> ranked = (List<ProductSearchCandidate>) state
                .value(ProductQueryGraphStateKeys.RANKED_PRODUCTS, List.class)
                .orElse(List.of());
        ProductComparisonResult comparison = comparisonBuilder.build(ranked, condition.comparisonTargets());
        return Map.of(ProductQueryGraphStateKeys.COMPARISON_RESULT, comparison);
    }

    private Map<String, Object> pqBuildResultList(OverAllState state) {
        // 占位节点：让 spec 的 11 节点列表完整保留；最终展示由 pq_final_response 统一拼装。
        return Map.of();
    }

    private Map<String, Object> pqFinalResponse(OverAllState state) {
        Map<String, Object> updates = new LinkedHashMap<>();
        ProductQueryCondition condition = state.value(
                ProductQueryGraphStateKeys.PRODUCT_QUERY_CONDITION, ProductQueryCondition.class
        ).orElse(null);
        @SuppressWarnings("unchecked")
        List<ProductSearchCandidate> ranked = (List<ProductSearchCandidate>) state
                .value(ProductQueryGraphStateKeys.RANKED_PRODUCTS, List.class)
                .orElse(List.of());
        ProductComparisonResult comparison = state.value(
                ProductQueryGraphStateKeys.COMPARISON_RESULT, ProductComparisonResult.class
        ).orElse(null);
        @SuppressWarnings("unchecked")
        List<String> degradedNotes = (List<String>) state
                .value(ProductQueryGraphStateKeys.DEGRADED_NOTES, List.class)
                .orElse(List.of());

        String status = state.value(ProductQueryGraphStateKeys.WORKFLOW_STATUS, "");
        String message;
        if (STATUS_CLARIFY.equals(status) && state.value(ProductQueryGraphStateKeys.NODE_MESSAGE).isPresent()) {
            message = state.value(ProductQueryGraphStateKeys.NODE_MESSAGE, "");
        } else if (comparison != null) {
            message = responseBuilder.buildComparisonResponse(condition, comparison, degradedNotes);
            updates.put(ProductQueryGraphStateKeys.WORKFLOW_STATUS, STATUS_OK);
        } else if (ranked.isEmpty()) {
            message = responseBuilder.buildListResponse(condition, List.of(), degradedNotes);
            updates.put(ProductQueryGraphStateKeys.WORKFLOW_STATUS, STATUS_NO_HITS);
        } else {
            message = responseBuilder.buildListResponse(condition, ranked, degradedNotes);
            updates.put(ProductQueryGraphStateKeys.WORKFLOW_STATUS, STATUS_OK);
        }
        updates.put(ProductQueryGraphStateKeys.NODE_MESSAGE, message);
        updates.putIfAbsent(ProductQueryGraphStateKeys.NEED_USER_INPUT, STATUS_CLARIFY.equals(status));

        persistPendingAction(state, condition, ranked);
        return updates;
    }

    private void persistPendingAction(
            OverAllState state,
            ProductQueryCondition condition,
            List<ProductSearchCandidate> ranked
    ) {
        if (condition == null || condition.needClarify()) {
            return;
        }
        try {
            String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
            String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
            int turnCount = state.value(ProductQueryGraphStateKeys.LAST_PRODUCT_QUERY_CONTEXT,
                            PendingProductQueryAction.class)
                    .map(PendingProductQueryAction::turnCount)
                    .map(prev -> prev + 1)
                    .orElse(1);
            long ttlHours = ragProperties.productQuery().pendingTtlHours();
            LocalDateTime now = LocalDateTime.now();
            PendingProductQueryAction record = new PendingProductQueryAction(
                    null,
                    userId,
                    conversationId,
                    condition,
                    ranked,
                    turnCount,
                    PendingProductQueryStatus.ACTIVE,
                    now,
                    now,
                    now.plusHours(ttlHours)
            );
            pendingRepository.save(record);
        } catch (RuntimeException exception) {
            log.warn("Failed to persist pending_product_query_actions; multi-turn refine may misbehave", exception);
        }
    }

    // -------- 辅助 --------

    private ProductQueryCondition requireCondition(OverAllState state) {
        return state.value(ProductQueryGraphStateKeys.PRODUCT_QUERY_CONDITION, ProductQueryCondition.class)
                .orElseThrow(() -> new IllegalStateException("PRODUCT_QUERY_CONDITION missing"));
    }

    private ProductQueryCondition previousCondition(OverAllState state) {
        return state.value(ProductQueryGraphStateKeys.LAST_PRODUCT_QUERY_CONTEXT, PendingProductQueryAction.class)
                .map(PendingProductQueryAction::condition)
                .orElse(null);
    }

    private String conversationMemory(OverAllState state) {
        @SuppressWarnings("unchecked")
        List<ConversationMessage> recent = (List<ConversationMessage>) state
                .value(GuideGraphStateKeys.RECENT_MESSAGES, List.class)
                .orElse(List.of());
        if (recent.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationMessage message : recent) {
            builder.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        return builder.toString().trim();
    }

    private String pickQuery(String preferred, String... fallbacks) {
        if (StringUtils.hasText(preferred)) {
            return preferred;
        }
        for (String fallback : fallbacks) {
            if (StringUtils.hasText(fallback)) {
                return fallback;
            }
        }
        return "";
    }

    private String requiredString(OverAllState state, String key) {
        return state.value(key)
                .map(Object::toString)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing graph state: " + key));
    }

    private void clearTransientState(Map<String, Object> updates) {
        for (String key : List.of(
                ProductQueryGraphStateKeys.PRODUCT_QUERY_CONDITION,
                ProductQueryGraphStateKeys.PRODUCT_QUERY_INTENT,
                ProductQueryGraphStateKeys.PRODUCT_HYDRATION_OPTIONS,
                ProductQueryGraphStateKeys.PRODUCT_SEARCH_FILTER,
                ProductQueryGraphStateKeys.PRODUCT_SEARCH_RESULT,
                ProductQueryGraphStateKeys.KEYWORD_HITS,
                ProductQueryGraphStateKeys.SEMANTIC_HITS,
                ProductQueryGraphStateKeys.FUSED_HITS,
                ProductQueryGraphStateKeys.RANKED_PRODUCTS,
                ProductQueryGraphStateKeys.POST_FILTERED_PRODUCTS,
                ProductQueryGraphStateKeys.POST_FILTER_REASONS,
                ProductQueryGraphStateKeys.COMPARISON_RESULT,
                ProductQueryGraphStateKeys.NEED_USER_INPUT,
                ProductQueryGraphStateKeys.NODE_MESSAGE,
                ProductQueryGraphStateKeys.WORKFLOW_STATUS,
                ProductQueryGraphStateKeys.DEGRADED_NOTES
        )) {
            updates.put(key, OverAllState.MARK_FOR_REMOVAL);
        }
    }

    private void recordDegraded(List<String> degradedNotes) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null || degradedNotes == null || degradedNotes.isEmpty()) {
            return;
        }
        for (String note : degradedNotes) {
            Counter.builder("rag_product_query_branch_degraded_total")
                    .tag("branch", note)
                    .register(registry)
                    .increment();
        }
    }
}
