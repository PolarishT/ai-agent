package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.document.api.DocumentCommandFacade;
import com.bytedance.ai.document.api.RagDocumentCreateRequest;
import com.bytedance.ai.document.api.RagDocumentView;
import com.bytedance.ai.graph.catalog.api.CatalogProductCreateRequest;
import com.bytedance.ai.graph.catalog.persistence.CatalogAttributeOutboxRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductContentRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogImportServiceTests {

    private CatalogProductRepository productRepository;
    private CatalogSkuRepository skuRepository;
    private CatalogProductContentRepository productContentRepository;
    private DocumentCommandFacade documentCommandFacade;
    private CatalogAttributeOutboxRepository attributeOutboxRepository;
    private RagJsonCodec jsonCodec;
    private CatalogImportService importService;

    @BeforeEach
    void setUp() {
        productRepository = mock(CatalogProductRepository.class);
        skuRepository = mock(CatalogSkuRepository.class);
        productContentRepository = mock(CatalogProductContentRepository.class);
        documentCommandFacade = mock(DocumentCommandFacade.class);
        attributeOutboxRepository = mock(CatalogAttributeOutboxRepository.class);
        jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
        importService = new CatalogImportService(
                productRepository,
                skuRepository,
                productContentRepository,
                documentCommandFacade,
                new ProductMarkdownRenderer(),
                attributeOutboxRepository,
                jsonCodec
        );
    }

    @Test
    void importOneWritesProductSkuKnowledgeFaqReviewsAndRagDocuments() {
        CatalogProductCreateRequest item = buildRequest("title");
        when(productRepository.save(
                eq("title"), eq("雅诗兰黛"), eq("美妆护肤"), eq("精华"),
                eq(new BigDecimal("1.0")), eq(new BigDecimal("1.0")), eq(new BigDecimal("2.0")),
                eq(3), eq("/image.png"), anyMap(), anyMap()
        )).thenReturn(stubProduct(7L));
        when(documentCommandFacade.createDocument(any(RagDocumentCreateRequest.class)))
                .thenReturn(stubDocumentView(901L));

        Long productId = importService.importOne(item);

        assertThat(productId).isEqualTo(7L);
        ArgumentCaptor<List<CatalogSkuRepository.SkuDraft>> skuCaptor = ArgumentCaptor.captor();
        verify(skuRepository).saveAll(eq(7L), skuCaptor.capture());
        assertThat(skuCaptor.getValue())
                .extracting(CatalogSkuRepository.SkuDraft::skuIndex)
                .containsExactly(0, 1);

        ArgumentCaptor<List<CatalogProductContentRepository.KnowledgeDraft>> knowledgeCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<List<CatalogProductContentRepository.FaqDraft>> faqCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<List<CatalogProductContentRepository.ReviewDraft>> reviewCaptor = ArgumentCaptor.captor();
        verify(productContentRepository).saveKnowledge(eq(7L), knowledgeCaptor.capture());
        verify(productContentRepository).saveFaqs(eq(7L), faqCaptor.capture());
        verify(productContentRepository).saveReviews(eq(7L), reviewCaptor.capture());
        assertThat(knowledgeCaptor.getValue()).extracting(CatalogProductContentRepository.KnowledgeDraft::knowledgeType)
                .containsExactly("MARKETING_DESCRIPTION", "REVIEW_SUMMARY");
        assertThat(faqCaptor.getValue()).extracting(CatalogProductContentRepository.FaqDraft::faqIndex)
                .containsExactly(0, 1);
        assertThat(reviewCaptor.getValue()).extracting(CatalogProductContentRepository.ReviewDraft::reviewIndex)
                .containsExactly(0, 1);

        ArgumentCaptor<RagDocumentCreateRequest> docCaptor = ArgumentCaptor.forClass(RagDocumentCreateRequest.class);
        verify(documentCommandFacade, org.mockito.Mockito.times(8)).createDocument(docCaptor.capture());
        assertThat(docCaptor.getAllValues()).extracting(RagDocumentCreateRequest::sourceUri)
                .containsExactly(
                        "product:7:profile",
                        "product:7:knowledge:MARKETING_DESCRIPTION",
                        "product:7:knowledge:REVIEW_SUMMARY",
                        "product:7:review-summary",
                        "product:7:faq:0",
                        "product:7:faq:1",
                        "product:7:review:0",
                        "product:7:review:1"
                );
        assertThat(docCaptor.getAllValues()).extracting(RagDocumentCreateRequest::sourceType)
                .containsExactly(
                        "PRODUCT_PROFILE",
                        "PRODUCT_KNOWLEDGE",
                        "PRODUCT_KNOWLEDGE",
                        "PRODUCT_REVIEW_SUMMARY",
                        "PRODUCT_FAQ",
                        "PRODUCT_FAQ",
                        "PRODUCT_REVIEW",
                        "PRODUCT_REVIEW"
                );
        assertThat(docCaptor.getAllValues())
                .allSatisfy(doc -> {
                    assertThat(doc.externalRef()).isEqualTo("7");
                    assertThat(doc.metadata()).containsEntry("productId", 7L);
                    assertThat(doc.metadata()).containsEntry("brand", "雅诗兰黛");
                    assertThat(doc.metadata()).containsEntry("category", "美妆护肤");
                    assertThat(doc.metadata()).containsEntry("subCategory", "精华");
                    assertThat(doc.metadata()).containsEntry("rawProductId", "p_beauty_001");
                    assertThat(doc.metadata()).containsEntry("rawSkuIds", List.of("s_p_beauty_001_0", "s_p_beauty_001_1"));
                });

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(attributeOutboxRepository).enqueue(eq(7L), eq("EXTRACT_ATTRIBUTES"), payloadCaptor.capture());
        Map<String, Object> parsed = jsonCodec.readMap(payloadCaptor.getValue());
        assertThat(parsed.get("triggeredBy")).isEqualTo("import");
        assertThat(parsed.get("enqueuedAtMs")).isInstanceOf(Number.class);
    }

    @Test
    void importOneTruncatesLongTitlesForDocumentCreate() {
        String longTitle = "极".repeat(150);
        CatalogProductCreateRequest item = buildRequest(longTitle);
        when(productRepository.save(anyString(), anyString(), anyString(), anyString(), any(),
                any(), any(), anyInt(), anyString(), anyMap(), anyMap())).thenReturn(stubProduct(8L));
        when(documentCommandFacade.createDocument(any())).thenReturn(stubDocumentView(902L));

        importService.importOne(item);

        ArgumentCaptor<RagDocumentCreateRequest> docCaptor = ArgumentCaptor.forClass(RagDocumentCreateRequest.class);
        verify(documentCommandFacade, org.mockito.Mockito.times(8)).createDocument(docCaptor.capture());
        assertThat(docCaptor.getAllValues().getFirst().title()).hasSize(100);
    }

    @Test
    void importOneBubblesUpWhenCreateDocumentFails() {
        CatalogProductCreateRequest item = buildRequest("title");
        when(productRepository.save(anyString(), anyString(), anyString(), anyString(), any(),
                any(), any(), anyInt(), anyString(), anyMap(), anyMap())).thenReturn(stubProduct(9L));
        when(documentCommandFacade.createDocument(any()))
                .thenThrow(new IllegalStateException("document failed"));

        assertThatThrownBy(() -> importService.importOne(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("document failed");

        verify(attributeOutboxRepository, never()).enqueue(any(), anyString(), anyString());
    }

    private CatalogProductCreateRequest buildRequest(String title) {
        return new CatalogProductCreateRequest(
                title,
                "雅诗兰黛",
                "美妆护肤",
                "精华",
                new BigDecimal("1.0"),
                new BigDecimal("1.0"),
                new BigDecimal("2.0"),
                3,
                "/image.png",
                Map.of(),
                Map.of("product_id", "p_beauty_001"),
                List.of(
                        new CatalogProductCreateRequest.SkuDraft(0, Map.of("color", "black"), new BigDecimal("1.0"), 1, Map.of("sku_id", "s_p_beauty_001_0")),
                        new CatalogProductCreateRequest.SkuDraft(1, Map.of("color", "white"), new BigDecimal("2.0"), 2, Map.of("sku_id", "s_p_beauty_001_1"))
                ),
                List.of(
                        new CatalogProductCreateRequest.KnowledgeDraft("MARKETING_DESCRIPTION", "卖点", "官方卖点", Map.of()),
                        new CatalogProductCreateRequest.KnowledgeDraft("REVIEW_SUMMARY", "评价总结", "评价总结正文", Map.of())
                ),
                List.of(
                        new CatalogProductCreateRequest.FaqDraft(0, "怎么用", "早晚用", Map.of()),
                        new CatalogProductCreateRequest.FaqDraft(1, "敏感肌能用吗", "先测试", Map.of())
                ),
                List.of(
                        new CatalogProductCreateRequest.ReviewDraft(0, "alice", 5, "很好用", "POSITIVE", Map.of()),
                        new CatalogProductCreateRequest.ReviewDraft(1, "bob", 3, "一般", "NEUTRAL", Map.of())
                )
        );
    }

    private CatalogProductRecord stubProduct(Long id) {
        return new CatalogProductRecord(
                id,
                "title",
                "雅诗兰黛",
                "美妆护肤",
                "精华",
                new BigDecimal("1.0"),
                new BigDecimal("1.0"),
                new BigDecimal("2.0"),
                3,
                "/image.png",
                "ACTIVE",
                Map.of(),
                Map.of("product_id", "p_beauty_001"),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private RagDocumentView stubDocumentView(Long id) {
        return new RagDocumentView(
                id, "PRODUCT_PROFILE", "product:7:profile", "7",
                "title", "PENDING", 0, 0, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now()
        );
    }
}
