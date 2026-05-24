package com.bytedance.ai.agent.eval;

import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnFacade;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.api.events.IntentDetectedPayload;
import com.bytedance.ai.agent.api.events.ToolResultPayload;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvalRunner 指标计算与 dataset loader 的单元测试。
 *
 * <p>不依赖 Spring，全部用 stub facade：caller 可控每题"召回的卡片 + 检出意图"，
 * 用以校验 HR@5 / P@5 / intent accuracy / 否定违反率 / category 分项的计算。
 */
class EvalRunnerTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());

    @Test
    void loaderReadsW2RegressionDataset() throws Exception {
        EvalRunner.Dataset dataset = new EvalDatasetLoader(jsonCodec).load("eval/w2-regression-cases.json");
        assertThat(dataset.cases()).hasSize(50);
        assertThat(dataset.intents()).containsExactly(
                "basic_recommend", "filter_by_attr", "compare", "negation", "out_of_scope");
        long negationCases = dataset.cases().stream().filter(c -> "negation".equals(c.category())).count();
        assertThat(negationCases).isEqualTo(10);
    }

    @Test
    void perfectFacadeProducesAllOnes() throws Exception {
        EvalRunner.Dataset dataset = new EvalDatasetLoader(jsonCodec).load("eval/w2-regression-cases.json");
        Map<String, EvalRunner.Case> byTurnId = new java.util.HashMap<>();
        for (EvalRunner.Case c : dataset.cases()) {
            byTurnId.put("eval-turn-" + c.id(), c);
        }
        EvalRunner runner = new EvalRunner(new StubFacade(byTurnId, this::perfectAnswer));
        EvalRunner.Report report = runner.run(dataset);

        assertThat(report.overall().hitRateAt5()).isEqualTo(1.0);
        assertThat(report.overall().intentAccuracy()).isEqualTo(1.0);
        assertThat(report.overall().negationViolationRate()).isEqualTo(0.0);
        // basic_recommend：每题只期望 1 个 SPU，top-5 命中 1 个 → P@5 = 0.2
        assertThat(report.byCategory().get("basic_recommend").precisionAt5()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void negationViolationDetectedWhenForbiddenTermAppearsInCard() {
        EvalRunner.Case aCase = new EvalRunner.Case(
                "n-1", "negation", "FILTER_BY_ATTR", "防晒不含酒精",
                List.of("SPU-X"), List.of("酒精")
        );
        EvalRunner.Dataset dataset = new EvalRunner.Dataset(
                "test", List.of("negation"), List.of(aCase));
        EvalRunner runner = new EvalRunner(new StubFacade(Map.of(), c -> StubAnswer.of(
                "FILTER_BY_ATTR",
                List.of(card("SPU-Z", "含酒精配方喷雾", "BrandZ")),
                List.of()
        )));
        EvalRunner.Report report = runner.run(dataset);
        assertThat(report.overall().negationViolationRate()).isEqualTo(1.0);
    }

    @Test
    void oosCaseIsTriviallyHitWhenNoCardsReturned() {
        EvalRunner.Case aCase = new EvalRunner.Case(
                "oos-1", "out_of_scope", "OUT_OF_SCOPE", "讲笑话",
                List.of(), List.of()
        );
        EvalRunner.Dataset dataset = new EvalRunner.Dataset(
                "test", List.of("out_of_scope"), List.of(aCase));
        EvalRunner runner = new EvalRunner(new StubFacade(Map.of(), c -> StubAnswer.of(
                "OUT_OF_SCOPE", List.of(), List.of())));
        EvalRunner.Report report = runner.run(dataset);
        assertThat(report.overall().hitRateAt5()).isEqualTo(1.0);
        assertThat(report.overall().intentAccuracy()).isEqualTo(1.0);
    }

    @Test
    void intentMismatchCountsAgainstAccuracyButHitCanStillSucceed() {
        EvalRunner.Case aCase = new EvalRunner.Case(
                "p-1", "basic_recommend", "RECOMMEND_VAGUE", "推荐双肩包",
                List.of("SPU-0001"), List.of()
        );
        EvalRunner.Dataset dataset = new EvalRunner.Dataset(
                "test", List.of("basic_recommend"), List.of(aCase));
        EvalRunner runner = new EvalRunner(new StubFacade(Map.of(), c -> StubAnswer.of(
                "FILTER_BY_ATTR",
                List.of(card("SPU-0001", "通勤包", "NorthFace")),
                List.of()
        )));
        EvalRunner.Report report = runner.run(dataset);
        assertThat(report.overall().hitRateAt5()).isEqualTo(1.0);
        assertThat(report.overall().intentAccuracy()).isEqualTo(0.0);
    }

    private StubAnswer perfectAnswer(EvalRunner.Case aCase) {
        List<SpuCardView> cards = aCase.expectedSpuRefs().stream()
                .map(ref -> card(ref, "T-" + ref, "B-" + ref))
                .toList();
        return StubAnswer.of(aCase.intent(), cards, List.of());
    }

    private SpuCardView card(String externalRef, String title, String brand) {
        return new SpuCardView(null, externalRef, title, brand, null, null, null, null,
                0.9d, List.of(), List.of(), "#1");
    }

    // ===== stubs =====

    private record StubAnswer(String intent, List<SpuCardView> cards, List<String> excludedFacets) {
        static StubAnswer of(String intent, List<SpuCardView> cards, List<String> excludedFacets) {
            return new StubAnswer(intent, cards, excludedFacets);
        }
    }

    private static class StubFacade implements AgentTurnFacade {
        private final Map<String, EvalRunner.Case> byTurnId;
        private final Function<EvalRunner.Case, StubAnswer> oracle;

        StubFacade(Map<String, EvalRunner.Case> byTurnId, Function<EvalRunner.Case, StubAnswer> oracle) {
            this.byTurnId = byTurnId;
            this.oracle = oracle;
        }

        @Override
        public Flux<AgentStreamEvent> turnStream(AgentTurnRequest request) {
            EvalRunner.Case caseRef = byTurnId.get(request.turnId());
            if (caseRef == null) {
                caseRef = new EvalRunner.Case(
                        request.turnId(), "stub", null, request.message(), List.of(), List.of());
            }
            StubAnswer answer = oracle.apply(caseRef);
            return Flux.just(
                    new AgentStreamEvent(UUID.randomUUID().toString(), "intent.detected", "corr",
                            new IntentDetectedPayload(
                                    answer.intent() == null ? null : IntentType.valueOf(answer.intent()),
                                    0.9d, "stub", Slot.empty())),
                    new AgentStreamEvent(UUID.randomUUID().toString(), "tool.result", "corr",
                            new ToolResultPayload("search_products", answer.cards(),
                                    Map.of(), null, answer.excludedFacets()))
            );
        }
    }
}
