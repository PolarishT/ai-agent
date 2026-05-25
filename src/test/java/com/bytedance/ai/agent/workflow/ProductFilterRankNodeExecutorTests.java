package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.application.AgentSseEventFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductFilterRankNodeExecutorTests {

    private final ProductFilterRankNodeExecutor executor = new ProductFilterRankNodeExecutor();

    @Test
    void filterDslPriceRangeKeepsOnlyAffordable() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.cards(List.of(
                card(1L, "ext-1", new BigDecimal("50")),
                card(2L, "ext-2", new BigDecimal("120")),
                card(3L, "ext-3", new BigDecimal("80"))
        ));
        state.slots(new Slot(List.of(), Slot.MustNot.empty(),
                new Slot.PriceRange(null, new BigDecimal("100")), null, List.of(), null));

        WorkflowDefinition.WorkflowNodeDefinition node = filterNode(Map.of("operators", List.of("priceRange")));
        executor.execute(execution(state), node);

        assertThat(state.cards()).extracting(SpuCardView::externalRef).containsExactly("ext-1", "ext-3");
    }

    @Test
    void filterDslMustNotExcludesMatchingIngredients() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.cards(List.of(
                cardWithText(1L, "ext-1", "传统皂基洗面奶"),
                cardWithText(2L, "ext-2", "氨基酸温和洁面")
        ));
        Slot.MustNot mustNot = new Slot.MustNot(List.of(), List.of(), List.of("皂基"));
        state.slots(new Slot(List.of(), mustNot, null, null, List.of(), null));

        executor.execute(execution(state), filterNode(Map.of("operators", List.of("mustNot"))));

        assertThat(state.cards()).extracting(SpuCardView::externalRef).containsExactly("ext-2");
    }

    @Test
    void filterDslUnknownOperatorFails() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.cards(List.of(card(1L, "ext-1", new BigDecimal("80"))));
        assertThatThrownBy(() -> executor.execute(execution(state),
                filterNode(Map.of("operators", List.of("hallucinatedOperator")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown product_filter operator");
    }

    @Test
    void rankDslPriceMatchPromotesAffordable() {
        WorkflowRuntimeState state = new WorkflowRuntimeState();
        state.cards(List.of(
                cardWithScore(1L, "ext-1", new BigDecimal("200"), 0.9d, 10),
                cardWithScore(2L, "ext-2", new BigDecimal("50"), 0.5d, 10)
        ));
        state.slots(new Slot(List.of(), Slot.MustNot.empty(),
                new Slot.PriceRange(null, new BigDecimal("100")), null, List.of(), null));

        WorkflowDefinition.WorkflowNodeDefinition node = rankNode(Map.of("score",
                List.of("retrievalScore", "priceMatch")));
        executor.execute(execution(state), node);

        // ext-2 = 0.5 + 0.2 = 0.7; ext-1 = 0.9 + 0 = 0.9 → ext-1 still wins despite price miss.
        assertThat(state.cards().getFirst().externalRef()).isEqualTo("ext-1");

        // 但若只看 priceMatch + stockAvailable，应反过来。
        WorkflowRuntimeState state2 = new WorkflowRuntimeState();
        state2.cards(List.of(
                cardWithScore(1L, "ext-1", new BigDecimal("200"), 0.9d, 10),
                cardWithScore(2L, "ext-2", new BigDecimal("50"), 0.5d, 10)
        ));
        state2.slots(state.slots());
        executor.execute(execution(state2), rankNode(Map.of("score", List.of("priceMatch", "stockAvailable"))));
        assertThat(state2.cards().getFirst().externalRef()).isEqualTo("ext-2");
    }

    private SpuCardView card(Long spuId, String externalRef, BigDecimal price) {
        return new SpuCardView(spuId, externalRef, "title-" + spuId, "brand", null,
                price, price, 5, 0.0, List.of(), List.of(), "#" + spuId);
    }

    private SpuCardView cardWithText(Long spuId, String externalRef, String title) {
        return new SpuCardView(spuId, externalRef, title, "brand", null,
                BigDecimal.TEN, BigDecimal.TEN, 5, 0.0, List.of(), List.of(), "#" + spuId);
    }

    private SpuCardView cardWithScore(Long spuId, String externalRef, BigDecimal price, double score, int stock) {
        return new SpuCardView(spuId, externalRef, "title-" + spuId, "brand", null,
                price, price, stock, score, List.of(), List.of(), "#" + spuId);
    }

    private WorkflowDefinition.WorkflowNodeDefinition filterNode(Map<String, Object> dsl) {
        return new WorkflowDefinition.WorkflowNodeDefinition(
                "product_filter", "product_filter", null, null, dsl, Map.of(), null, null);
    }

    private WorkflowDefinition.WorkflowNodeDefinition rankNode(Map<String, Object> dsl) {
        return new WorkflowDefinition.WorkflowNodeDefinition(
                "product_rank", "product_rank", null, null, Map.of(), dsl, null, null);
    }

    private WorkflowExecution execution(WorkflowRuntimeState state) {
        AgentTurnRequest req = new AgentTurnRequest("u", "c", "msg", "t", null, null, List.of());
        EcommerceWorkflowEngine.WorkflowRequest wr =
                new EcommerceWorkflowEngine.WorkflowRequest(req, "t", "corr", null);
        return new WorkflowExecution(wr, null, state, new AgentSseEventFactory(), null);
    }
}
