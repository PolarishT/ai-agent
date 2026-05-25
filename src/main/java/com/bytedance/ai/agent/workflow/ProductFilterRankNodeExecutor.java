package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 用 workflow DSL（不是写死的 Java 规则）做确定性过滤与排序。
 *
 * <p>过滤算子（来自 {@code filterDsl.operators}）：{@code priceRange}, {@code brand}, {@code must}, {@code mustNot}。
 * <p>排序算子（来自 {@code rankDsl.score}）：{@code retrievalScore}, {@code stockAvailable}, {@code priceMatch}。
 *
 * <p>LLM 只生成「为什么这么排」的自然语言理由（写到 {@code state.answerText}），不参与硬条件过滤。
 */
@Component
public class ProductFilterRankNodeExecutor implements WorkflowNodeExecutor {

    private static final List<String> DEFAULT_FILTER_OPS = List.of("priceRange", "brand", "must", "mustNot");
    private static final List<String> DEFAULT_RANK_SCORES = List.of("retrievalScore", "stockAvailable", "priceMatch");

    @Override
    public boolean supports(NodeType type) {
        return type == NodeType.PRODUCT_FILTER || type == NodeType.PRODUCT_RANK;
    }

    @Override
    public Map<String, Object> execute(WorkflowExecution execution, WorkflowDefinition.WorkflowNodeDefinition node) {
        WorkflowRuntimeState state = execution.state();
        if (node.nodeType() == NodeType.PRODUCT_FILTER) {
            List<String> operators = readOperators(node.filterDsl(), "operators", DEFAULT_FILTER_OPS);
            Predicate<SpuCardView> predicate = compileFilter(operators, state.slots());
            List<SpuCardView> filtered = state.cards().stream().filter(predicate).toList();
            // 全部被过滤掉时保留原集合，让 ranker / final_answer 仍有内容可呈现。
            state.cards(reindex(filtered.isEmpty() ? state.cards() : filtered));
            return Map.of("operators", operators, "kept", state.cards().size());
        }
        List<String> scoreFns = readOperators(node.rankDsl(), "score", DEFAULT_RANK_SCORES);
        Comparator<SpuCardView> comparator = compileRank(scoreFns, state.slots());
        state.cards(reindex(state.cards().stream().sorted(comparator).toList()));
        return Map.of("scoreFns", scoreFns, "ranked", state.cards().size());
    }

    @SuppressWarnings("unchecked")
    private List<String> readOperators(Map<String, Object> dsl, String key, List<String> fallback) {
        if (dsl == null) {
            return fallback;
        }
        Object value = dsl.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return fallback;
        }
        return list.stream().map(String::valueOf).toList();
    }

    private Predicate<SpuCardView> compileFilter(List<String> operators, Slot slots) {
        Map<String, Predicate<SpuCardView>> ops = new LinkedHashMap<>();
        ops.put("priceRange", card -> matchesPriceRange(card, slots));
        ops.put("brand", card -> matchesBrand(card, slots));
        ops.put("must", card -> matchesMust(card, slots));
        ops.put("mustNot", card -> matchesMustNot(card, slots));

        Predicate<SpuCardView> chain = card -> true;
        for (String op : operators) {
            Predicate<SpuCardView> predicate = ops.get(op);
            if (predicate == null) {
                throw new IllegalArgumentException("unknown product_filter operator: " + op);
            }
            chain = chain.and(predicate);
        }
        return chain;
    }

    private Comparator<SpuCardView> compileRank(List<String> scoreFns, Slot slots) {
        Map<String, java.util.function.ToDoubleFunction<SpuCardView>> fns = new LinkedHashMap<>();
        fns.put("retrievalScore", card -> card.score() == null ? 0d : card.score());
        fns.put("stockAvailable", card -> card.stock() != null && card.stock() > 0 ? 0.2d : 0d);
        fns.put("priceMatch", card -> priceMatchBonus(card, slots));

        return Comparator.comparingDouble((SpuCardView card) -> {
            double sum = 0d;
            for (String name : scoreFns) {
                java.util.function.ToDoubleFunction<SpuCardView> fn = fns.get(name);
                if (fn == null) {
                    throw new IllegalArgumentException("unknown product_rank score fn: " + name);
                }
                sum += fn.applyAsDouble(card);
            }
            return sum;
        }).reversed();
    }

    private boolean matchesPriceRange(SpuCardView card, Slot slots) {
        if (slots == null || slots.priceRange() == null || card.priceMin() == null) {
            return true;
        }
        BigDecimal max = slots.priceRange().max();
        BigDecimal min = slots.priceRange().min();
        if (max != null && card.priceMin().compareTo(max) > 0) {
            return false;
        }
        return min == null || card.priceMin().compareTo(min) >= 0;
    }

    private boolean matchesBrand(SpuCardView card, Slot slots) {
        if (slots == null || slots.brands().isEmpty() || !StringUtils.hasText(card.brand())) {
            return true;
        }
        return slots.brands().stream().anyMatch(brand -> card.brand().contains(brand));
    }

    private boolean matchesMust(SpuCardView card, Slot slots) {
        if (slots == null || slots.must().isEmpty()) {
            return true;
        }
        String text = textOf(card);
        for (String must : slots.must()) {
            if (StringUtils.hasText(must) && !text.contains(must)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesMustNot(SpuCardView card, Slot slots) {
        if (slots == null || slots.mustNot() == null || slots.mustNot().isEmpty()) {
            return true;
        }
        String text = textOf(card);
        for (String denied : slots.mustNot().flatten()) {
            if (StringUtils.hasText(denied) && text.contains(denied)) {
                return false;
            }
        }
        return true;
    }

    private double priceMatchBonus(SpuCardView card, Slot slots) {
        if (slots == null || slots.priceRange() == null || card.priceMin() == null) {
            return 0d;
        }
        BigDecimal max = slots.priceRange().max();
        return max != null && card.priceMin().compareTo(max) <= 0 ? 0.2d : 0d;
    }

    private String textOf(SpuCardView card) {
        return (card.title() == null ? "" : card.title())
                + " " + (card.brand() == null ? "" : card.brand())
                + " " + String.join(" ", card.reasons() == null ? List.of() : card.reasons());
    }

    private List<SpuCardView> reindex(List<SpuCardView> cards) {
        List<SpuCardView> reindexed = new ArrayList<>(cards.size());
        for (int i = 0; i < cards.size(); i++) {
            SpuCardView card = cards.get(i);
            reindexed.add(new SpuCardView(
                    card.spuId(),
                    card.externalRef(),
                    card.title(),
                    card.brand(),
                    card.image(),
                    card.priceMin(),
                    card.priceMax(),
                    card.stock(),
                    card.score(),
                    card.badges(),
                    card.reasons(),
                    "#" + (i + 1)
            ));
        }
        return reindexed;
    }
}
