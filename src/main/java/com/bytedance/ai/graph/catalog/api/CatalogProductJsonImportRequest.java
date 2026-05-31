package com.bytedance.ai.graph.catalog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 外部 JSON 商品导入请求。
 *
 * <p>字段命名沿用商品资料平台导出的 snake_case，便于直接对接采集脚本：
 * <pre>{@code
 * {
 *   "product_id": "p_food_025",
 *   "title": "...",
 *   "brand": "...",
 *   "category": "...",
 *   "sub_category": "...",
 *   "base_price": 89.0,
 *   "image_path": "...",
 *   "skus": [
 *     {"sku_id": "s_..._1", "properties": {"口味": "..."}, "price": 89.0}
 *   ],
 *   "rag_knowledge": {
 *     "marketing_description": "...",
 *     "official_faq": [{"question": "...", "answer": "..."}],
 *     "user_reviews": [{"nickname": "...", "rating": 5, "content": "..."}]
 *   }
 * }
 * }</pre>
 *
 * <p>映射结果会喂给 {@code CatalogProductCreateRequest}，复用 catalog 既有的批量导入事务链路。
 */
public record CatalogProductJsonImportRequest(
        @JsonProperty("product_id")
        @Size(max = 128, message = "外部商品编号长度不能超过 128 个字符")
        String productId,

        @JsonProperty("title")
        @NotBlank(message = "商品标题不能为空")
        @Size(max = 512, message = "商品标题长度不能超过 512 个字符")
        String title,

        @JsonProperty("brand")
        @Size(max = 255, message = "品牌长度不能超过 255 个字符")
        String brand,

        @JsonProperty("category")
        @NotBlank(message = "商品类目不能为空")
        @Size(max = 128, message = "商品类目长度不能超过 128 个字符")
        String category,

        @JsonProperty("sub_category")
        @Size(max = 128, message = "商品子类目长度不能超过 128 个字符")
        String subCategory,

        @JsonProperty("base_price")
        @DecimalMin(value = "0.0", message = "基础价格不能为负")
        BigDecimal basePrice,

        @JsonProperty("image_path")
        String imagePath,

        @JsonProperty("skus")
        @Valid
        List<SkuItem> skus,

        @JsonProperty("rag_knowledge")
        @Valid
        RagKnowledge ragKnowledge
) {

    public record SkuItem(
            @JsonProperty("sku_id")
            @Size(max = 128, message = "外部 SKU 编号长度不能超过 128 个字符")
            String skuId,

            @JsonProperty("properties")
            Map<String, Object> properties,

            @JsonProperty("price")
            @DecimalMin(value = "0.0", message = "SKU 价格不能为负")
            BigDecimal price
    ) {
    }

    public record RagKnowledge(
            @JsonProperty("marketing_description")
            String marketingDescription,

            @JsonProperty("official_faq")
            @Valid
            List<FaqItem> officialFaq,

            @JsonProperty("user_reviews")
            @Valid
            List<ReviewItem> userReviews
    ) {
    }

    public record FaqItem(
            @JsonProperty("question")
            @NotBlank(message = "FAQ 问题不能为空")
            String question,

            @JsonProperty("answer")
            @NotBlank(message = "FAQ 答案不能为空")
            String answer
    ) {
    }

    public record ReviewItem(
            @JsonProperty("nickname")
            String nickname,

            @JsonProperty("rating")
            Integer rating,

            @JsonProperty("content")
            @NotBlank(message = "评价内容不能为空")
            String content
    ) {
    }
}
