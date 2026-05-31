package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogProductCreateRequest;
import com.bytedance.ai.graph.catalog.persistence.CatalogAttributeOutboxRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductContentRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRepository;
import com.bytedance.ai.document.api.DocumentCommandFacade;
import com.bytedance.ai.document.api.RagDocumentCreateRequest;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单条 Product 的事务化导入服务。拆出独立 bean 是为了让 Spring 代理生效——
 * {@link CatalogCommandService#importBatch} 调用本类时会通过容器代理，
 * 单条失败由本类抛错回滚，由调用方记录失败明细并继续下一条。
 *
 * <p>导入流程末尾调 {@link CatalogAttributeOutboxRepository#enqueue}，
 * 利用同事务保证「Product 落库 + 多篇 rag_documents + outbox 行」三者要么全部成功要么全部回滚，
 * 后台 dispatcher 看到 outbox 行后才会真正投递 RocketMQ，参见 {@code AGENT.md §3.9}。
 */
@Service
class CatalogImportService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);
    private static final String IMPORT_TRIGGER = "import";
    private static final int RAG_DOCUMENT_TITLE_MAX = 100;
    private static final String PRODUCT_PROFILE = "PRODUCT_PROFILE";
    private static final String PRODUCT_KNOWLEDGE = "PRODUCT_KNOWLEDGE";
    private static final String PRODUCT_FAQ = "PRODUCT_FAQ";
    private static final String PRODUCT_REVIEW = "PRODUCT_REVIEW";
    private static final String PRODUCT_REVIEW_SUMMARY = "PRODUCT_REVIEW_SUMMARY";
    private static final String REVIEW_SUMMARY = "REVIEW_SUMMARY";

    private final CatalogProductRepository productRepository;
    private final CatalogSkuRepository skuRepository;
    private final CatalogProductContentRepository productContentRepository;
    private final DocumentCommandFacade documentCommandFacade;
    private final ProductMarkdownRenderer markdownRenderer;
    private final CatalogAttributeOutboxRepository attributeOutboxRepository;
    private final RagJsonCodec jsonCodec;

    CatalogImportService(
            CatalogProductRepository productRepository,
            CatalogSkuRepository skuRepository,
            CatalogProductContentRepository productContentRepository,
            DocumentCommandFacade documentCommandFacade,
            ProductMarkdownRenderer markdownRenderer,
            CatalogAttributeOutboxRepository attributeOutboxRepository,
            RagJsonCodec jsonCodec
    ) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.productContentRepository = productContentRepository;
        this.documentCommandFacade = documentCommandFacade;
        this.markdownRenderer = markdownRenderer;
        this.attributeOutboxRepository = attributeOutboxRepository;
        this.jsonCodec = jsonCodec;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long importOne(CatalogProductCreateRequest item) {
        CatalogProductRecord product = productRepository.save(
                item.title(),
                item.brand(),
                item.category(),
                item.subCategory(),
                item.basePrice(),
                item.priceMin(),
                item.priceMax(),
                item.totalStock() == null ? 0 : item.totalStock(),
                item.imagePath(),
                safeMap(item.attributesJson()),
                safeMap(item.rawJson())
        );

        List<CatalogSkuRepository.SkuDraft> skuDrafts = safeList(item.skus()).stream()
                .map(s -> new CatalogSkuRepository.SkuDraft(
                        s.skuIndex() == null ? 0 : s.skuIndex(),
                        safeMap(s.propertiesJson()),
                        s.price(),
                        s.stock() == null ? 0 : s.stock(),
                        safeMap(s.rawJson())
                ))
                .toList();
        skuRepository.saveAll(product.id(), skuDrafts);

        productContentRepository.saveKnowledge(product.id(), toKnowledgeDrafts(item));
        productContentRepository.saveFaqs(product.id(), toFaqDrafts(item));
        productContentRepository.saveReviews(product.id(), toReviewDrafts(item));

        createRagDocuments(product, item);

        attributeOutboxRepository.enqueue(product.id(), "EXTRACT_ATTRIBUTES", buildOutboxPayload(IMPORT_TRIGGER));

        log.info(
                "catalog product imported: productId={}, title={}",
                product.id(),
                product.title()
        );
        return product.id();
    }

    private String buildOutboxPayload(String trigger) {
        return jsonCodec.write(Map.of(
                "triggeredBy", trigger,
                "enqueuedAtMs", System.currentTimeMillis()
        ));
    }

    private void createRagDocuments(CatalogProductRecord product, CatalogProductCreateRequest item) {
        createDocument(product, PRODUCT_PROFILE, "product:" + product.id() + ":profile",
                item.title(), markdownRenderer.renderProfile(item), baseDocumentMetadata(product, item, PRODUCT_PROFILE));

        for (CatalogProductCreateRequest.KnowledgeDraft knowledge : safeList(item.knowledge())) {
            String knowledgeType = normalizeKnowledgeType(knowledge.knowledgeType());
            Map<String, Object> metadata = baseDocumentMetadata(product, item, PRODUCT_KNOWLEDGE);
            metadata.put("knowledgeType", knowledgeType);
            createDocument(product, PRODUCT_KNOWLEDGE, "product:" + product.id() + ":knowledge:" + knowledgeType,
                    knowledge.title() == null ? item.title() : knowledge.title(),
                    markdownRenderer.renderKnowledge(knowledge),
                    metadata);
            if (REVIEW_SUMMARY.equals(knowledgeType)) {
                Map<String, Object> summaryMetadata = baseDocumentMetadata(product, item, PRODUCT_REVIEW_SUMMARY);
                summaryMetadata.put("knowledgeType", knowledgeType);
                createDocument(product, PRODUCT_REVIEW_SUMMARY, "product:" + product.id() + ":review-summary",
                        item.title() + " review summary",
                        markdownRenderer.renderReviewSummary(item.title(), knowledge.content()),
                        summaryMetadata);
            }
        }

        for (CatalogProductCreateRequest.FaqDraft faq : safeList(item.faqs())) {
            int faqIndex = faq.faqIndex() == null ? 0 : faq.faqIndex();
            Map<String, Object> metadata = baseDocumentMetadata(product, item, PRODUCT_FAQ);
            metadata.put("faqIndex", faqIndex);
            createDocument(product, PRODUCT_FAQ, "product:" + product.id() + ":faq:" + faqIndex,
                    item.title() + " FAQ " + faqIndex,
                    markdownRenderer.renderFaq(faq),
                    metadata);
        }

        for (CatalogProductCreateRequest.ReviewDraft review : safeList(item.reviews())) {
            int reviewIndex = review.reviewIndex() == null ? 0 : review.reviewIndex();
            Map<String, Object> metadata = baseDocumentMetadata(product, item, PRODUCT_REVIEW);
            metadata.put("reviewIndex", reviewIndex);
            createDocument(product, PRODUCT_REVIEW, "product:" + product.id() + ":review:" + reviewIndex,
                    item.title() + " review " + reviewIndex,
                    markdownRenderer.renderReview(review),
                    metadata);
        }
    }

    private void createDocument(
            CatalogProductRecord product,
            String sourceType,
            String sourceUri,
            String title,
            String content,
            Map<String, Object> metadata
    ) {
        documentCommandFacade.createDocument(new RagDocumentCreateRequest(
                sourceType,
                sourceUri,
                String.valueOf(product.id()),
                truncateForDocumentTitle(title),
                content,
                metadata
        ));
    }

    private Map<String, Object> baseDocumentMetadata(
            CatalogProductRecord product,
            CatalogProductCreateRequest item,
            String sourceType
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("productId", product.id());
        metadata.put("sourceType", sourceType);
        if (StringUtils.hasText(item.brand())) {
            metadata.put("brand", item.brand());
        }
        if (StringUtils.hasText(item.category())) {
            metadata.put("category", item.category());
        }
        if (StringUtils.hasText(item.subCategory())) {
            metadata.put("subCategory", item.subCategory());
        }
        Object rawProductId = safeMap(item.rawJson()).get("product_id");
        if (rawProductId != null) {
            metadata.put("rawProductId", rawProductId);
        }
        List<String> rawSkuIds = safeList(item.skus()).stream()
                .map(CatalogProductCreateRequest.SkuDraft::rawJson)
                .filter(Objects::nonNull)
                .map(raw -> raw.get("sku_id"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
        if (!rawSkuIds.isEmpty()) {
            metadata.put("rawSkuIds", rawSkuIds);
        }
        return metadata;
    }

    private List<CatalogProductContentRepository.KnowledgeDraft> toKnowledgeDrafts(CatalogProductCreateRequest item) {
        return safeList(item.knowledge()).stream()
                .map(draft -> new CatalogProductContentRepository.KnowledgeDraft(
                        normalizeKnowledgeType(draft.knowledgeType()),
                        draft.title(),
                        draft.content(),
                        sha256Hex(draft.content()),
                        safeMap(draft.metadata())
                ))
                .toList();
    }

    private List<CatalogProductContentRepository.FaqDraft> toFaqDrafts(CatalogProductCreateRequest item) {
        return safeList(item.faqs()).stream()
                .map(draft -> new CatalogProductContentRepository.FaqDraft(
                        draft.faqIndex() == null ? 0 : draft.faqIndex(),
                        draft.question(),
                        draft.answer(),
                        sha256Hex(draft.question() + "\n" + draft.answer()),
                        safeMap(draft.metadata())
                ))
                .toList();
    }

    private List<CatalogProductContentRepository.ReviewDraft> toReviewDrafts(CatalogProductCreateRequest item) {
        return safeList(item.reviews()).stream()
                .map(draft -> new CatalogProductContentRepository.ReviewDraft(
                        draft.reviewIndex() == null ? 0 : draft.reviewIndex(),
                        draft.nickname(),
                        draft.rating(),
                        draft.content(),
                        sha256Hex(draft.content()),
                        normalizeNullable(draft.sentiment()),
                        safeMap(draft.metadata())
                ))
                .toList();
    }

    private String normalizeKnowledgeType(String value) {
        if (!StringUtils.hasText(value)) {
            return "OTHER";
        }
        return value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(java.util.Locale.ROOT) : null;
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String truncateForDocumentTitle(String title) {
        if (title == null) {
            return null;
        }
        if (title.length() <= RAG_DOCUMENT_TITLE_MAX) {
            return title;
        }
        return title.substring(0, RAG_DOCUMENT_TITLE_MAX);
    }

}
