package com.bytedance.ai.graph.catalog.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Product-oriented catalog import request.
 */
public record CatalogProductCreateRequest(
        @NotBlank(message = "商品标题不能为空")
        @Size(max = 512, message = "商品标题长度不能超过 512 个字符")
        String title,

        @Size(max = 255, message = "品牌长度不能超过 255 个字符")
        String brand,

        @NotBlank(message = "商品类目不能为空")
        @Size(max = 128, message = "商品类目长度不能超过 128 个字符")
        String category,

        @Size(max = 128, message = "商品子类目长度不能超过 128 个字符")
        String subCategory,

        @DecimalMin(value = "0.0", message = "基础价格不能为负")
        BigDecimal basePrice,

        @DecimalMin(value = "0.0", message = "价格下界不能为负")
        BigDecimal priceMin,

        @DecimalMin(value = "0.0", message = "价格上界不能为负")
        BigDecimal priceMax,

        @PositiveOrZero(message = "库存不能为负")
        Integer totalStock,

        String imagePath,

        Map<String, Object> attributesJson,

        Map<String, Object> rawJson,

        @Valid
        List<SkuDraft> skus,

        @Valid
        List<KnowledgeDraft> knowledge,

        @Valid
        List<FaqDraft> faqs,

        @Valid
        List<ReviewDraft> reviews
) {
    public record SkuDraft(
            Integer skuIndex,
            Map<String, Object> propertiesJson,
            @DecimalMin(value = "0.0", message = "SKU 价格不能为负")
            BigDecimal price,
            @PositiveOrZero(message = "SKU 库存不能为负")
            Integer stock,
            Map<String, Object> rawJson
    ) {
    }

    public record KnowledgeDraft(
            @NotBlank(message = "知识类型不能为空")
            String knowledgeType,
            String title,
            @NotBlank(message = "知识内容不能为空")
            String content,
            Map<String, Object> metadata
    ) {
    }

    public record FaqDraft(
            Integer faqIndex,
            @NotBlank(message = "FAQ 问题不能为空")
            String question,
            @NotBlank(message = "FAQ 答案不能为空")
            String answer,
            Map<String, Object> metadata
    ) {
    }

    public record ReviewDraft(
            Integer reviewIndex,
            String nickname,
            Integer rating,
            @NotBlank(message = "评价内容不能为空")
            String content,
            String sentiment,
            Map<String, Object> metadata
    ) {
    }
}
