package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;

@Component
public class CompareMatrixBuilder {

    private static final int MAX_EXTRA_ASPECTS = 8;
    private static final int MAX_REASON_LENGTH = 120;
    private static final int MAX_DESCRIPTION_SNIPPET_LENGTH = 80;

    private static final Set<String> BASE_ATTRIBUTES = Set.of(
            "品牌",
            "价格",
            "库存",
            "匹配理由"
    );

    private static final Set<String> PRICE_LIKE_ASPECTS = Set.of(
            "价格",
            "预算",
            "性价比",
            "便宜",
            "划算"
    );

    private static final Set<String> STOCK_LIKE_ASPECTS = Set.of(
            "库存",
            "现货",
            "有货"
    );

    public CompareMatrixView build(
            List<SpuCardView> cards,
            List<ResolvedProductCandidate> candidates,
            List<String> compareAspects,
            String userGoal
    ) {
        List<CardCandidatePair> pairs = align(cards, candidates);

        if (pairs.isEmpty()) {
            return new CompareMatrixView(List.of(), List.of(), null, null);
        }

        List<String> aspects = normalizedAspects(compareAspects);

        List<CompareMatrixView.ProductColumn> products = pairs.stream()
                .map(pair -> new CompareMatrixView.ProductColumn(
                        pair.card().refId(),
                        pair.card().spuId(),
                        pair.card().externalRef(),
                        pair.card().title()
                ))
                .toList();

        List<CompareMatrixView.AttributeRow> rows = new ArrayList<>();
        rows.add(row("品牌", pairs.stream().map(pair -> valueOrUnknown(pair.spu().brand())).toList()));
        rows.add(row("价格", pairs.stream().map(pair -> priceText(pair.spu())).toList()));
        rows.add(row("库存", pairs.stream().map(pair -> stockText(pair.spu())).toList()));
        rows.add(row("匹配理由", pairs.stream().map(pair -> matchReason(pair.candidate())).toList()));

        for (String aspect : aspects) {
            rows.add(row(
                    aspect,
                    pairs.stream()
                            .map(pair -> aspectValue(pair.spu(), aspect))
                            .toList()
            ));
        }

        CardCandidatePair recommended = recommend(pairs, userGoal, aspects);

        return new CompareMatrixView(
                products,
                rows,
                recommended == null ? null : recommended.card().refId(),
                recommendationReason(recommended, userGoal, aspects)
        );
    }

    private List<CardCandidatePair> align(
            List<SpuCardView> cards,
            List<ResolvedProductCandidate> candidates
    ) {
        if (cards == null || cards.isEmpty() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        int size = Math.min(cards.size(), candidates.size());
        List<CardCandidatePair> pairs = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            SpuCardView card = cards.get(i);
            ResolvedProductCandidate candidate = candidates.get(i);

            if (card == null || candidate == null || candidate.spu() == null) {
                continue;
            }

            pairs.add(new CardCandidatePair(card, candidate));
        }

        return pairs;
    }

    private CompareMatrixView.AttributeRow row(String attribute, List<String> values) {
        return new CompareMatrixView.AttributeRow(attribute, values == null ? List.of() : values);
    }

    private List<String> normalizedAspects(List<String> compareAspects) {
        if (compareAspects == null || compareAspects.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> aspects = new LinkedHashSet<>();

        for (String rawAspect : compareAspects) {
            if (!StringUtils.hasText(rawAspect)) {
                continue;
            }

            String aspect = rawAspect.trim();

            if (shouldSkipAspect(aspect)) {
                continue;
            }

            aspects.add(aspect);

            if (aspects.size() >= MAX_EXTRA_ASPECTS) {
                break;
            }
        }

        return List.copyOf(aspects);
    }

    private boolean shouldSkipAspect(String aspect) {
        if (BASE_ATTRIBUTES.contains(aspect)) {
            return true;
        }

        // 这些已经被基础行“价格”覆盖，避免重复生成“预算/便宜/划算”等价格行
        if (PRICE_LIKE_ASPECTS.contains(aspect)) {
            return true;
        }

        // 这些已经被基础行“库存”覆盖
        return STOCK_LIKE_ASPECTS.contains(aspect);
    }

    private String aspectValue(CatalogSpuView spu, String aspect) {
        if (spu == null || !StringUtils.hasText(aspect)) {
            return "暂无明确字段";
        }

        String direct = directFieldValue(spu, aspect);
        if (direct != null) {
            return direct;
        }

        String attributeValue = attributeValue(spu.attributes(), aspect);
        if (attributeValue != null) {
            return attributeValue;
        }

        String descriptionValue = descriptionValue(spu.descriptionMd(), aspect);
        if (descriptionValue != null) {
            return descriptionValue;
        }

        return "暂无明确字段";
    }

    private String directFieldValue(CatalogSpuView spu, String aspect) {
        if ("品牌".equals(aspect)) {
            return valueOrUnknown(spu.brand());
        }

        if (PRICE_LIKE_ASPECTS.contains(aspect)) {
            return priceText(spu);
        }

        if (STOCK_LIKE_ASPECTS.contains(aspect)) {
            return stockText(spu);
        }

        return null;
    }

    private String attributeValue(Map<String, Object> attributes, String aspect) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }

        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (!StringUtils.hasText(key) || value == null) {
                continue;
            }

            String normalizedKey = key.trim();

            if (normalizedKey.contains(aspect) || aspect.contains(normalizedKey)) {
                return String.valueOf(value);
            }
        }

        return null;
    }

    private String descriptionValue(String descriptionMd, String aspect) {
        if (!StringUtils.hasText(descriptionMd) || !descriptionMd.contains(aspect)) {
            return null;
        }

        String compact = descriptionMd
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        for (String sentence : compact.split("[。！？!?；;]")) {
            if (!sentence.contains(aspect)) {
                continue;
            }

            String value = sentence.trim();
            if (!StringUtils.hasText(value)) {
                continue;
            }

            return truncate(value, MAX_DESCRIPTION_SNIPPET_LENGTH);
        }

        return "描述中提到" + aspect;
    }

    private CardCandidatePair recommend(
            List<CardCandidatePair> pairs,
            String userGoal,
            List<String> aspects
    ) {
        if (pairs == null || pairs.isEmpty()) {
            return null;
        }

        if (isBudgetGoal(userGoal, aspects)) {
            return pairs.stream()
                    .filter(pair -> pair.card().priceMin() != null)
                    .min((left, right) -> left.card().priceMin().compareTo(right.card().priceMin()))
                    .orElse(pairs.get(0));
        }

        return pairs.stream()
                .max((left, right) -> Double.compare(
                        safeScore(left.card().score()),
                        safeScore(right.card().score())
                ))
                .orElse(pairs.get(0));
    }

    private boolean isBudgetGoal(String userGoal, List<String> aspects) {
        if (containsAny(userGoal, PRICE_LIKE_ASPECTS)) {
            return true;
        }

        if (aspects == null || aspects.isEmpty()) {
            return false;
        }

        return aspects.stream().anyMatch(aspect -> containsAny(aspect, PRICE_LIKE_ASPECTS));
    }

    private boolean containsAny(String text, Set<String> tokens) {
        if (!StringUtils.hasText(text) || tokens == null || tokens.isEmpty()) {
            return false;
        }

        for (String token : tokens) {
            if (StringUtils.hasText(token) && text.contains(token)) {
                return true;
            }
        }

        return false;
    }

    private String recommendationReason(
            CardCandidatePair recommended,
            String userGoal,
            List<String> aspects
    ) {
        if (recommended == null) {
            return null;
        }

        String refId = recommended.card().refId();

        if (isBudgetGoal(userGoal, aspects)) {
            return refId + " 在当前候选中价格更友好。";
        }

        if (StringUtils.hasText(userGoal)) {
            return refId + " 当前检索匹配度较高，适合作为“" + userGoal.trim() + "”的候选。";
        }

        return refId + " 当前检索匹配度较高，可作为综合推荐候选。";
    }

    private String matchReason(ResolvedProductCandidate candidate) {
        if (candidate == null || !StringUtils.hasText(candidate.reason())) {
            return "暂无明确匹配理由";
        }

        return truncate(candidate.reason().trim(), MAX_REASON_LENGTH);
    }

    private String stockText(CatalogSpuView spu) {
        if (spu == null || spu.stock() == null) {
            return "未知";
        }

        return spu.stock() + " 件";
    }

    private String priceText(CatalogSpuView spu) {
        if (spu == null) {
            return "未知";
        }

        return priceText(spu.priceMin(), spu.priceMax());
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

    private double safeScore(Double score) {
        if (score == null || score.isNaN() || score.isInfinite()) {
            return 0.0d;
        }

        return Math.max(0.0d, score);
    }

    private String valueOrUnknown(String value) {
        return StringUtils.hasText(value) ? value : "未知";
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private record CardCandidatePair(
            SpuCardView card,
            ResolvedProductCandidate candidate
    ) {
        CatalogSpuView spu() {
            return candidate.spu();
        }
    }
}