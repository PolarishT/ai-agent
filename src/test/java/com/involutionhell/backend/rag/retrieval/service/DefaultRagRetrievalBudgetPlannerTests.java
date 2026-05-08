package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRagRetrievalBudgetPlannerTests {

    private final DefaultRagRetrievalBudgetPlanner planner = new DefaultRagRetrievalBudgetPlanner(RagProperties.defaults());

    @Test
    void spreadsMultiQueryBudgetInsteadOfMultiplyingFanOut() {
        RagRetrievalBudget single = planner.plan("explain rag", List.of("explain rag"), null, 3, false);
        RagRetrievalBudget expanded = planner.plan(
                "explain rag",
                List.of("explain rag", "rag retrieval", "rag answer"),
                null,
                3,
                false
        );

        assertThat(single.perQueryTopK()).isEqualTo(6);
        assertThat(expanded.perQueryTopK()).isEqualTo(4);
        assertThat(expanded.perQueryTopK() * expanded.queryCount()).isLessThanOrEqualTo(24);
    }

    @Test
    void boostsSemanticCandidatesWhenFilterIsPresent() {
        RagRetrievalBudget withoutFilter = planner.plan("explain retrieval quality", List.of("explain retrieval quality"), null, 3, false);
        RagRetrievalBudget withFilter = planner.plan(
                "explain retrieval quality",
                List.of("explain retrieval quality"),
                RagSearchFilter.of("docs/", null, null),
                3,
                false
        );

        assertThat(withFilter.semanticCandidateTopK()).isGreaterThan(withoutFilter.semanticCandidateTopK());
    }

    @Test
    void boostsKeywordCandidatesForShortEnglishOrCodeLikeQueries() {
        RagRetrievalBudget natural = planner.plan(
                "请解释这个系统如何进行检索增强生成并处理上下文窗口",
                List.of("请解释这个系统如何进行检索增强生成并处理上下文窗口"),
                null,
                3,
                false
        );
        RagRetrievalBudget codeLike = planner.plan("RagAskService.search()", List.of("RagAskService.search()"), null, 3, false);

        assertThat(codeLike.keywordCandidateTopK()).isGreaterThan(natural.keywordCandidateTopK());
    }

    @Test
    void boostsSemanticCandidatesForLongNaturalLanguageQueries() {
        String longQuestion = "请完整解释在线问答链路如何完成查询改写、查询扩展、混合检索、上下文融合以及流式答案生成，并说明每个阶段的降级策略";

        RagRetrievalBudget budget = planner.plan(longQuestion, List.of(longQuestion), null, 3, false);

        assertThat(budget.semanticCandidateTopK()).isGreaterThan(18);
    }

    @Test
    void retryBudgetIsWiderButBounded() {
        RagRetrievalBudget initial = planner.plan("explain rag", List.of("explain rag"), null, 10, false);
        RagRetrievalBudget retry = planner.plan("explain rag", List.of("explain rag"), null, 10, true);

        assertThat(retry.perQueryTopK()).isGreaterThan(initial.perQueryTopK());
        assertThat(retry.perQueryTopK()).isLessThanOrEqualTo(48);
        assertThat(retry.progressiveEnabled()).isFalse();
    }
}
