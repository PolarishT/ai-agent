package com.bytedance.ai.agent.eval;

import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.AgentTurnFacade;
import com.bytedance.ai.agent.api.AgentTurnRequest;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.api.events.IntentDetectedPayload;
import com.bytedance.ai.agent.api.events.ToolResultPayload;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * W2 回归集执行器（test scope）。
 *
 * <p>给一份 {@link Dataset} + 一个 {@link AgentTurnFacade}：
 * <ol>
 *   <li>逐题构造 {@code AgentTurnRequest}，跑 {@code facade.turnStream(...).collectList().block()}；</li>
 *   <li>解析事件流，提取最后一次 {@code tool.result} 的 {@code cards}（顺序保留）+ {@code excludedFacets}
 *       + {@code intent.detected.intent}；</li>
 *   <li>计算 HR@5 / P@5 / 否定违反率 / intent accuracy，按 category 分项汇总。</li>
 * </ol>
 *
 * <p>本类与 Spring 解耦：caller 自己拼装 facade（Spring 集成测试 or stub）。
 */
public final class EvalRunner {

    public static final int TOP_K = 5;

    private final AgentTurnFacade facade;

    public EvalRunner(AgentTurnFacade facade) {
        this.facade = facade;
    }

    /**
     * 跑完整个 dataset；逐题串行调 facade，最后聚合指标。
     * 不并发：避免 LLM 速率限制和不可控的相互干扰；50 题×几秒一般在分钟级可完成。
     */
    public Report run(Dataset dataset) {
        List<CaseResult> results = new ArrayList<>(dataset.cases().size());
        for (Case aCase : dataset.cases()) {
            results.add(runOne(aCase));
        }
        return summarize(dataset, results);
    }

    /**
     * 单题执行：构造确定性 turnId（{@code eval-turn-<caseId>}）以便 stub facade 反查 case，
     * 用一个独立的 conversationId 隔离每题（避免上一题的 ConversationMemory 影响下一题的 REFINE 判定）。
     * 异常被 swallow 进 {@link CaseResult#error}，让回归集即使 LLM 偶发抖动也能跑完。
     */
    private CaseResult runOne(Case aCase) {
        String conversationId = "eval-conv-" + UUID.randomUUID();
        String turnId = "eval-turn-" + aCase.id();
        AgentTurnRequest request = new AgentTurnRequest(
                "eval-user",
                conversationId,
                aCase.query(),
                turnId,
                null,
                null,
                null
        );
        long startedNanos = System.nanoTime();
        List<AgentStreamEvent> events;
        Throwable error = null;
        try {
            events = facade.turnStream(request).collectList().block(Duration.ofSeconds(60));
            if (events == null) {
                events = List.of();
            }
        } catch (RuntimeException exception) {
            events = List.of();
            error = exception;
        }
        long latencyMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();

        String detectedIntent = null;
        List<SpuCardView> cards = List.of();
        List<String> excludedFacets = List.of();
        for (AgentStreamEvent event : events) {
            if (event.data() instanceof IntentDetectedPayload intentPayload && detectedIntent == null) {
                detectedIntent = intentPayload.intent() == null ? null : intentPayload.intent().name();
            } else if (event.data() instanceof ToolResultPayload toolResult) {
                // 保留最后一次 tool.result（COMPARE 走 compare_products 一次足够；search 也只触发一次）
                cards = toolResult.cards();
                excludedFacets = toolResult.excludedFacets();
            }
        }
        return new CaseResult(aCase, detectedIntent, cards, excludedFacets, latencyMs, error);
    }

    /**
     * 把每题结果聚合成最终 {@link Report}：
     * <ul>
     *   <li>{@code overall.*} 是全集求平均；</li>
     *   <li>{@code byCategory} 按 dataset 声明的 intents 顺序输出，方便 EVAL_RESULTS.md 渲染表格；</li>
     *   <li>{@code negationViolationRate} 只在 {@code negation} 类目内计算，其它类目为 null。</li>
     * </ul>
     */
    Report summarize(Dataset dataset, List<CaseResult> results) {
        // 全局聚合
        int total = results.size();
        int intentHits = 0;
        int hitAt5 = 0;
        double precisionSum = 0d;
        int negationCases = 0;
        int negationViolations = 0;
        long latencySum = 0;

        // 按 category 分项
        Map<String, CategoryAcc> byCategory = new LinkedHashMap<>();
        for (String c : dataset.intents()) {
            byCategory.put(c, new CategoryAcc());
        }

        for (CaseResult r : results) {
            CategoryAcc acc = byCategory.computeIfAbsent(r.aCase().category(), key -> new CategoryAcc());
            acc.total++;
            latencySum += r.latencyMs();

            if (intentMatches(r)) {
                intentHits++;
                acc.intentHit++;
            }

            int hit = countHits(r);
            double p5 = (double) hit / TOP_K;
            precisionSum += p5;
            acc.precisionSum += p5;

            if (hitOrTrivial(r, hit)) {
                hitAt5++;
                acc.hitAt5++;
            }

            if ("negation".equalsIgnoreCase(r.aCase().category())) {
                negationCases++;
                acc.negationCases++;
                if (negationViolated(r)) {
                    negationViolations++;
                    acc.negationViolations++;
                }
            }
        }

        Map<String, CategoryMetrics> categoryMetrics = new LinkedHashMap<>();
        for (Map.Entry<String, CategoryAcc> entry : byCategory.entrySet()) {
            CategoryAcc a = entry.getValue();
            if (a.total == 0) continue;
            categoryMetrics.put(entry.getKey(), new CategoryMetrics(
                    a.total,
                    (double) a.hitAt5 / a.total,
                    a.precisionSum / a.total,
                    (double) a.intentHit / a.total,
                    a.negationCases == 0 ? null : (double) a.negationViolations / a.negationCases
            ));
        }

        Metrics overall = new Metrics(
                total,
                total == 0 ? 0d : (double) hitAt5 / total,
                total == 0 ? 0d : precisionSum / total,
                total == 0 ? 0d : (double) intentHits / total,
                negationCases == 0 ? null : (double) negationViolations / negationCases,
                total == 0 ? 0 : latencySum / total
        );
        return new Report(dataset.datasetVersion(), overall, categoryMetrics, results);
    }

    private boolean intentMatches(CaseResult r) {
        if (r.aCase().intent() == null || r.detectedIntent() == null) {
            return false;
        }
        return r.aCase().intent().equalsIgnoreCase(r.detectedIntent());
    }

    private int countHits(CaseResult r) {
        if (r.aCase().expectedSpuRefs().isEmpty()) {
            return 0;
        }
        Set<String> expected = Set.copyOf(r.aCase().expectedSpuRefs());
        int hit = 0;
        for (int i = 0; i < Math.min(r.cards().size(), TOP_K); i++) {
            SpuCardView card = r.cards().get(i);
            if (card.externalRef() != null && expected.contains(card.externalRef())) {
                hit++;
            }
        }
        return hit;
    }

    /**
     * HR@5 命中规则：
     * <ul>
     *   <li>有期望 SPU 时：top-5 中至少出现一个期望 SPU 即算命中；</li>
     *   <li>无期望 SPU 时（OOS / 完全反选）：trivially-true ⇔ 完全没召回任何 SPU。
     *       这一支用来惩罚 OOS 漏判为业务 query 而错召回的情况。</li>
     * </ul>
     */
    private boolean hitOrTrivial(CaseResult r, int hit) {
        if (r.aCase().expectedSpuRefs().isEmpty()) {
            return r.cards().isEmpty();
        }
        return hit > 0;
    }

    /**
     * 否定违反检测：top-5 任一卡片的 title / brand 字段（小写化）包含任何一个 {@code mustNotTags}
     * 即算违反。这里只用 title + brand，避免对 reasons 中的"无 X"反向语义误报。
     */
    private boolean negationViolated(CaseResult r) {
        List<String> forbidden = r.aCase().mustNotTags();
        if (forbidden.isEmpty() || r.cards().isEmpty()) {
            return false;
        }
        for (int i = 0; i < Math.min(r.cards().size(), TOP_K); i++) {
            SpuCardView card = r.cards().get(i);
            String haystack = ((nullToEmpty(card.title()) + " " + nullToEmpty(card.brand()))).toLowerCase(Locale.ROOT);
            for (String term : forbidden) {
                if (haystack.contains(term.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ===== data records =====

    public record Dataset(
            String datasetVersion,
            List<String> intents,
            List<Case> cases
    ) {
        public Dataset {
            cases = cases == null ? List.of() : List.copyOf(cases);
            intents = intents == null ? List.of() : List.copyOf(intents);
        }
    }

    public record Case(
            String id,
            String category,
            String intent,
            String query,
            List<String> expectedSpuRefs,
            List<String> mustNotTags
    ) {
        public Case {
            expectedSpuRefs = expectedSpuRefs == null ? List.of() : List.copyOf(expectedSpuRefs);
            mustNotTags = mustNotTags == null ? List.of() : List.copyOf(mustNotTags);
        }
    }

    public record CaseResult(
            Case aCase,
            String detectedIntent,
            List<SpuCardView> cards,
            List<String> excludedFacets,
            long latencyMs,
            Throwable error
    ) {
        public CaseResult {
            cards = cards == null ? List.of() : List.copyOf(cards);
            excludedFacets = excludedFacets == null ? List.of() : List.copyOf(excludedFacets);
        }
    }

    public record Metrics(
            int totalCases,
            double hitRateAt5,
            double precisionAt5,
            double intentAccuracy,
            /** null = 数据集里没有 negation 题；否则为 violations/negationCases */
            Double negationViolationRate,
            long avgLatencyMs
    ) {
    }

    public record CategoryMetrics(
            int totalCases,
            double hitRateAt5,
            double precisionAt5,
            double intentAccuracy,
            Double negationViolationRate
    ) {
    }

    public record Report(
            String datasetVersion,
            Metrics overall,
            Map<String, CategoryMetrics> byCategory,
            List<CaseResult> caseResults
    ) {
        public Report {
            byCategory = byCategory == null ? Map.of() : Map.copyOf(byCategory);
            caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        }
    }

    private static final class CategoryAcc {
        int total;
        int hitAt5;
        double precisionSum;
        int intentHit;
        int negationCases;
        int negationViolations;
    }
}
