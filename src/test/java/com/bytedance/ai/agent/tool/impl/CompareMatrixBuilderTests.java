package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.catalog.api.CatalogSkuView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompareMatrixBuilderTests {

    private final CompareMatrixBuilder builder = new CompareMatrixBuilder();

    @Test
    void returnsEmptyMatrixWhenNoAlignedPairs() {
        CompareMatrixView matrix = builder.build(
                List.of(card(1L, "SPU-A", "#1", 0.9d)),
                List.of(),
                List.of("保湿"),
                "综合推荐"
        );

        assertThat(matrix.products()).isEmpty();
        assertThat(matrix.rows()).isEmpty();
        assertThat(matrix.recommendedRefId()).isNull();
        assertThat(matrix.recommendationReason()).isNull();
    }

    @Test
    void alignsByMinSizeAndSkipsInvalidPairs() {
        CompareMatrixView matrix = builder.build(
                List.of(card(1L, "SPU-A", "#1", 0.9d), card(2L, "SPU-B", "#2", 0.8d)),
                List.of(new ResolvedProductCandidate(spu(1L, "SPU-A", "Alpha", "描述", Map.of()), 0.9d, "命中 A")),
                List.of("保湿"),
                null
        );

        assertThat(matrix.products()).hasSize(1);
        assertThat(matrix.products().getFirst().refId()).isEqualTo("#1");
        assertThat(row(matrix, "品牌").values()).containsExactly("Alpha");
        assertThat(row(matrix, "匹配理由").values()).containsExactly("命中 A");
    }

    @Test
    void normalizedAspectsDropsBasePriceAndStockLikeDimensionsAndCapsAtEight() {
        List<String> aspects = List.of(
                " 品牌 ",
                "价格",
                "预算",
                "性价比",
                "便宜",
                "划算",
                "库存",
                "现货",
                "有货",
                "成分",
                "肤感",
                "敏感肌适配",
                "保湿",
                "香味",
                "包装",
                "质地",
                "功效",
                "产地",
                "售后"
        );

        CompareMatrixView matrix = builder.build(
                List.of(card(1L, "SPU-A", "#1", 0.9d), card(2L, "SPU-B", "#2", 0.8d)),
                List.of(
                        new ResolvedProductCandidate(spu(1L, "SPU-A", "Alpha", "含烟酰胺，肤感清爽。", Map.of("成分", "烟酰胺")), 0.9d, "命中 A"),
                        new ResolvedProductCandidate(spu(2L, "SPU-B", "Beta", "质地滋润。", Map.of("成分", "玻色因")), 0.8d, "命中 B")
                ),
                aspects,
                null
        );

        assertThat(matrix.rows()).extracting(CompareMatrixView.AttributeRow::attribute)
                .containsExactly(
                        "品牌",
                        "价格",
                        "库存",
                        "匹配理由",
                        "成分",
                        "肤感",
                        "敏感肌适配",
                        "保湿",
                        "香味",
                        "包装",
                        "质地",
                        "功效"
                );
    }

    @Test
    void descriptionAndMatchReasonAreTrimmedAndTruncated() {
        String longReason = "  " + "命中理由".repeat(30);
        String longSentence = "这是一段包含敏感肌适配的说明".repeat(10);
        CompareMatrixView matrix = builder.build(
                List.of(card(1L, "SPU-A", "#1", 0.9d), card(2L, "SPU-B", "#2", 0.8d)),
                List.of(
                        new ResolvedProductCandidate(spu(1L, "SPU-A", "Alpha", longSentence + "。后续说明", Map.of()), 0.9d, longReason),
                        new ResolvedProductCandidate(spu(2L, "SPU-B", "Beta", "未提到目标维度", Map.of()), 0.8d, null)
                ),
                List.of("敏感肌适配"),
                null
        );

        assertThat(row(matrix, "匹配理由").values().getFirst()).hasSize(120);
        assertThat(row(matrix, "匹配理由").values().get(1)).isEqualTo("暂无明确匹配理由");
        assertThat(row(matrix, "敏感肌适配").values().getFirst()).hasSize(80);
        assertThat(row(matrix, "敏感肌适配").values().get(1)).isEqualTo("暂无明确字段");
    }

    @Test
    void recommendUsesLowestPriceForBudgetGoalAndSafeScoreOtherwise() {
        CompareMatrixView budgetMatrix = builder.build(
                List.of(card(1L, "SPU-A", "#1", Double.NaN), card(2L, "SPU-B", "#2", Double.POSITIVE_INFINITY)),
                List.of(
                        new ResolvedProductCandidate(spu(1L, "SPU-A", "Alpha", "描述", Map.of()), Double.NaN, "A"),
                        new ResolvedProductCandidate(spu(2L, "SPU-B", "Beta", "描述", Map.of()), Double.POSITIVE_INFINITY, "B")
                ),
                List.of("保湿"),
                "预算优先"
        );
        assertThat(budgetMatrix.recommendedRefId()).isEqualTo("#2");
        assertThat(budgetMatrix.recommendationReason()).isEqualTo("#2 在当前候选中价格更友好。");

        CompareMatrixView scoreMatrix = builder.build(
                List.of(card(1L, "SPU-A", "#1", Double.NaN), card(2L, "SPU-B", "#2", 0.5d)),
                List.of(
                        new ResolvedProductCandidate(spu(1L, "SPU-A", "Alpha", "描述", Map.of()), Double.NaN, "A"),
                        new ResolvedProductCandidate(spu(2L, "SPU-B", "Beta", "描述", Map.of()), 0.5d, "B")
                ),
                List.of("保湿"),
                "保湿优先"
        );
        assertThat(scoreMatrix.recommendedRefId()).isEqualTo("#2");
        assertThat(scoreMatrix.recommendationReason()).isEqualTo("#2 当前检索匹配度较高，适合作为“保湿优先”的候选。");
    }

    private CompareMatrixView.AttributeRow row(CompareMatrixView matrix, String attribute) {
        return matrix.rows().stream()
                .filter(row -> row.attribute().equals(attribute))
                .findFirst()
                .orElseThrow();
    }

    private SpuCardView card(Long spuId, String externalRef, String refId, Double score) {
        return new SpuCardView(
                spuId,
                externalRef,
                "title-" + spuId,
                "brand",
                null,
                spuId == 1L ? new BigDecimal("299") : new BigDecimal("199"),
                spuId == 1L ? new BigDecimal("299") : new BigDecimal("199"),
                spuId == 1L ? 8 : 20,
                score,
                List.of(),
                List.of(),
                refId
        );
    }

    private CatalogSpuView spu(Long id, String externalRef, String brand, String descriptionMd, Map<String, Object> attributes) {
        return new CatalogSpuView(
                id,
                externalRef,
                "title-" + id,
                brand,
                "美妆/面霜",
                id == 1L ? new BigDecimal("299") : new BigDecimal("199"),
                id == 1L ? new BigDecimal("299") : new BigDecimal("199"),
                id == 1L ? 8 : 20,
                descriptionMd,
                List.of(),
                null,
                attributes,
                "DONE",
                "ACTIVE",
                1000L + id,
                List.<CatalogSkuView>of(),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }
}
