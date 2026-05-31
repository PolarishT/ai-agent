package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogProductCreateRequest;
import com.bytedance.ai.graph.catalog.api.CatalogProductJsonImportRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把外部 {@link CatalogProductJsonImportRequest} 翻译成 catalog 内部使用的
 * {@link CatalogProductCreateRequest}。
 *
 * <p>外部 product_id / sku_id 不进 SPU 主键，统一塞到 rawJson；catalog 内部继续用 bigserial。
 * priceMin / priceMax 由 SKU 自动聚合，避免外部漏传时构造出错位的范围。
 *
 * <p>库存语义：上游导入数据不带 stock 字段，约定「一条 SKU 记录 = 一件实物」——
 * 每个 sku 的 stock 写死 1，{@code product.total_stock} 即 {@code skus.size()}。
 * 这样 ProductHardFilter#mustHaveStock 才能过 catalog_product.total_stock &gt; 0 这条检索硬过滤。
 */
@Component
class CatalogJsonImportMapper {

    private static final String KNOWLEDGE_TYPE_MARKETING = "MARKETING_DESCRIPTION";
    private static final int SKU_DEFAULT_STOCK = 1;

    CatalogProductCreateRequest toCreateRequest(CatalogProductJsonImportRequest source) {
        Objects.requireNonNull(source, "source");

        List<CatalogProductCreateRequest.SkuDraft> skus = mapSkus(source.skus());
        List<CatalogProductCreateRequest.KnowledgeDraft> knowledge = mapKnowledge(source.ragKnowledge());
        List<CatalogProductCreateRequest.FaqDraft> faqs = mapFaqs(source.ragKnowledge());
        List<CatalogProductCreateRequest.ReviewDraft> reviews = mapReviews(source.ragKnowledge());

        BigDecimal priceMin = skus.stream()
                .map(CatalogProductCreateRequest.SkuDraft::price)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(source.basePrice());
        BigDecimal priceMax = skus.stream()
                .map(CatalogProductCreateRequest.SkuDraft::price)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(source.basePrice());
        int totalStock = skus.size();

        Map<String, Object> rawJson = new LinkedHashMap<>();
        if (StringUtils.hasText(source.productId())) {
            rawJson.put("product_id", source.productId());
        }

        return new CatalogProductCreateRequest(
                source.title(),
                source.brand(),
                source.category(),
                source.subCategory(),
                source.basePrice(),
                priceMin,
                priceMax,
                totalStock,
                source.imagePath(),
                Map.of(),
                rawJson,
                skus,
                knowledge,
                faqs,
                reviews
        );
    }

    private List<CatalogProductCreateRequest.SkuDraft> mapSkus(List<CatalogProductJsonImportRequest.SkuItem> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<CatalogProductCreateRequest.SkuDraft> drafts = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            CatalogProductJsonImportRequest.SkuItem sku = source.get(index);
            Map<String, Object> rawSku = new LinkedHashMap<>();
            if (StringUtils.hasText(sku.skuId())) {
                rawSku.put("sku_id", sku.skuId());
            }
            drafts.add(new CatalogProductCreateRequest.SkuDraft(
                    index,
                    sku.properties() == null ? Map.of() : sku.properties(),
                    sku.price(),
                    SKU_DEFAULT_STOCK,
                    rawSku
            ));
        }
        return drafts;
    }

    private List<CatalogProductCreateRequest.KnowledgeDraft> mapKnowledge(CatalogProductJsonImportRequest.RagKnowledge ragKnowledge) {
        if (ragKnowledge == null || !StringUtils.hasText(ragKnowledge.marketingDescription())) {
            return List.of();
        }
        return List.of(new CatalogProductCreateRequest.KnowledgeDraft(
                KNOWLEDGE_TYPE_MARKETING,
                null,
                ragKnowledge.marketingDescription(),
                Map.of()
        ));
    }

    private List<CatalogProductCreateRequest.FaqDraft> mapFaqs(CatalogProductJsonImportRequest.RagKnowledge ragKnowledge) {
        if (ragKnowledge == null || ragKnowledge.officialFaq() == null || ragKnowledge.officialFaq().isEmpty()) {
            return List.of();
        }
        List<CatalogProductJsonImportRequest.FaqItem> source = ragKnowledge.officialFaq();
        List<CatalogProductCreateRequest.FaqDraft> drafts = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            CatalogProductJsonImportRequest.FaqItem faq = source.get(index);
            drafts.add(new CatalogProductCreateRequest.FaqDraft(
                    index,
                    faq.question(),
                    faq.answer(),
                    Map.of()
            ));
        }
        return drafts;
    }

    private List<CatalogProductCreateRequest.ReviewDraft> mapReviews(CatalogProductJsonImportRequest.RagKnowledge ragKnowledge) {
        if (ragKnowledge == null || ragKnowledge.userReviews() == null || ragKnowledge.userReviews().isEmpty()) {
            return List.of();
        }
        List<CatalogProductJsonImportRequest.ReviewItem> source = ragKnowledge.userReviews();
        List<CatalogProductCreateRequest.ReviewDraft> drafts = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            CatalogProductJsonImportRequest.ReviewItem review = source.get(index);
            drafts.add(new CatalogProductCreateRequest.ReviewDraft(
                    index,
                    review.nickname(),
                    review.rating(),
                    review.content(),
                    inferSentiment(review.rating()),
                    Map.of()
            ));
        }
        return drafts;
    }

    private String inferSentiment(Integer rating) {
        if (rating == null) {
            return null;
        }
        if (rating >= 4) {
            return "POSITIVE";
        }
        if (rating <= 2) {
            return "NEGATIVE";
        }
        return "NEUTRAL";
    }
}
