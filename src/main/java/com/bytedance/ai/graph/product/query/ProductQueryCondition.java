package com.bytedance.ai.graph.product.query;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品查询 condition：由 {@code ProductQueryConditionLlmService} 从用户消息解析得到，
 * 再经 {@code ProductQueryConditionSanitizer} / {@code ProductQueryConditionValidator}
 * 净化与校验后写入子图状态。
 *
 * <p>所有 {@code List<String>} 与嵌套 record 字段在 canonical constructor 中做防御性 copy；
 * 货币字段统一用 {@link BigDecimal}。
 *
 * @param rawQuery              用户原始消息（含语气词、口语）；保留原句作为语义检索 embedding 文本
 * @param normalizedQuery       去除冗余的归一化查询
 * @param intent                QUERY / REFINE / COMPARE / RESET
 * @param queryMode             KEYWORD_ONLY / SEMANTIC_ONLY / HYBRID
 * @param keywordQuery          关键词检索分支使用的查询
 * @param semanticQuery         语义检索分支使用的查询（可与 keywordQuery 不同）
 * @param categoryTerms         命中的品类正向关键词
 * @param excludeCategoryTerms  排除的品类关键词（"不要苏打水"）
 * @param brandTerms            命中的品牌正向关键词
 * @param excludeBrandTerms     排除的品牌关键词
 * @param includeTerms          必须包含的关键词
 * @param excludeTerms          必须排除的关键词
 * @param attributes            结构化属性（color / size / material / capacity）
 * @param priceMin              价格下界（含），BigDecimal 防浮点误差
 * @param priceMax              价格上界（含）
 * @param mustHaveStock         是否强制 stock>0；null 表示未显式指定，由配置兜底
 * @param sort                  PRICE_ASC / PRICE_DESC / RELEVANCE / RATING
 * @param refineType            INHERIT / OVERRIDE / APPEND / RESET（用于多轮合并）
 * @param comparisonTargets     对比目标的 1-based 索引（基于上一轮候选列表）
 * @param comparisonTargetTexts 用户直接点名的 2-3 个商品名、型号或外部编号
 * @param compareFocus          用户本轮对比的决策关注点（如性价比 / 通勤 / 补水 / 敏感肌）
 * @param requestedDimensions   用户显式要求展示的对比维度（如续航 / 价格 / 用户评价）
 * @param needComparison        是否需要走对比节点而非常规列表回复
 * @param confidence            LLM 自评置信度 [0, 1]
 * @param needClarify           是否需要澄清（低置信度 / 缺关键 slot 时为 true）
 * @param missingSlots          缺失字段列表（澄清提示用）
 */
public record ProductQueryCondition(
        String rawQuery,
        String normalizedQuery,
        String intent,
        String queryMode,
        String keywordQuery,
        String semanticQuery,
        List<String> categoryTerms,
        List<String> excludeCategoryTerms,
        List<String> brandTerms,
        List<String> excludeBrandTerms,
        List<String> includeTerms,
        List<String> excludeTerms,
        ProductAttributesCondition attributes,
        BigDecimal priceMin,
        BigDecimal priceMax,
        Boolean mustHaveStock,
        String sort,
        String refineType,
        List<Integer> comparisonTargets,
        List<String> comparisonTargetTexts,
        List<String> compareFocus,
        List<String> requestedDimensions,
        boolean needComparison,
        double confidence,
        boolean needClarify,
        List<String> missingSlots
) {

    public ProductQueryCondition {
        categoryTerms = copyOrEmpty(categoryTerms);
        excludeCategoryTerms = copyOrEmpty(excludeCategoryTerms);
        brandTerms = copyOrEmpty(brandTerms);
        excludeBrandTerms = copyOrEmpty(excludeBrandTerms);
        includeTerms = copyOrEmpty(includeTerms);
        excludeTerms = copyOrEmpty(excludeTerms);
        attributes = attributes == null ? ProductAttributesCondition.empty() : attributes;
        comparisonTargets = copyOrEmpty(comparisonTargets);
        comparisonTargetTexts = copyOrEmpty(comparisonTargetTexts);
        compareFocus = copyOrEmpty(compareFocus);
        requestedDimensions = copyOrEmpty(requestedDimensions);
        missingSlots = copyOrEmpty(missingSlots);
    }

    public ProductQueryCondition(
            String rawQuery,
            String normalizedQuery,
            String intent,
            String queryMode,
            String keywordQuery,
            String semanticQuery,
            List<String> categoryTerms,
            List<String> excludeCategoryTerms,
            List<String> brandTerms,
            List<String> excludeBrandTerms,
            List<String> includeTerms,
            List<String> excludeTerms,
            ProductAttributesCondition attributes,
            BigDecimal priceMin,
            BigDecimal priceMax,
            Boolean mustHaveStock,
            String sort,
            String refineType,
            List<Integer> comparisonTargets,
            boolean needComparison,
            double confidence,
            boolean needClarify,
            List<String> missingSlots
    ) {
        this(
                rawQuery,
                normalizedQuery,
                intent,
                queryMode,
                keywordQuery,
                semanticQuery,
                categoryTerms,
                excludeCategoryTerms,
                brandTerms,
                excludeBrandTerms,
                includeTerms,
                excludeTerms,
                attributes,
                priceMin,
                priceMax,
                mustHaveStock,
                sort,
                refineType,
                comparisonTargets,
                List.of(),
                List.of(),
                List.of(),
                needComparison,
                confidence,
                needClarify,
                missingSlots
        );
    }

    public static ProductQueryCondition empty(String rawQuery) {
        return new ProductQueryCondition(
                rawQuery,
                rawQuery,
                "QUERY",
                "HYBRID",
                rawQuery,
                rawQuery,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ProductAttributesCondition.empty(),
                null,
                null,
                null,
                "RELEVANCE",
                "RESET",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                0.0d,
                true,
                List.of("intent")
        );
    }

    private static <T> List<T> copyOrEmpty(List<T> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }
}
