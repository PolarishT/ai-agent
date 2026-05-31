package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogImportRequest;
import com.bytedance.ai.graph.catalog.api.CatalogImportSummary;
import com.bytedance.ai.graph.catalog.api.CatalogProductCreateRequest;
import com.bytedance.ai.graph.catalog.persistence.CatalogAttributeOutboxRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogCommandServiceTests {

    private CatalogImportService catalogImportService;
    private CatalogProductRepository productRepository;
    private CatalogAttributeOutboxRepository attributeOutboxRepository;
    private RagJsonCodec jsonCodec;
    private CatalogCommandService commandService;

    @BeforeEach
    void setUp() {
        catalogImportService = mock(CatalogImportService.class);
        productRepository = mock(CatalogProductRepository.class);
        attributeOutboxRepository = mock(CatalogAttributeOutboxRepository.class);
        jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
        commandService = new CatalogCommandService(
                catalogImportService,
                productRepository,
                attributeOutboxRepository,
                jsonCodec,
                mock(CatalogJsonImportMapper.class)
        );
    }

    @Test
    void importBatchAggregatesSuccessAndFailure() {
        CatalogProductCreateRequest ok = sampleItem("ok");
        CatalogProductCreateRequest bad = sampleItem("bad");
        when(catalogImportService.importOne(ok)).thenReturn(101L);
        when(catalogImportService.importOne(bad)).thenThrow(new IllegalStateException("duplicate source_uri"));

        CatalogImportSummary summary = commandService.importBatch(new CatalogImportRequest(List.of(ok, bad)));

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.succeededIds()).containsExactly(101L);
        assertThat(summary.failures()).hasSize(1);
        assertThat(summary.failures().getFirst().externalRef()).isEqualTo("title-bad");
        assertThat(summary.failures().getFirst().reason()).contains("duplicate source_uri");
    }

    @Test
    void requestAttributeExtractionEnqueuesOutboxWithManualTrigger() {
        Long productId = 42L;
        when(productRepository.findById(productId)).thenReturn(Optional.of(stubProduct(productId)));

        commandService.requestAttributeExtraction(productId);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(attributeOutboxRepository).enqueue(eq(productId), eq("REFRESH_ATTRIBUTES"), payloadCaptor.capture());

        Map<String, Object> parsed = jsonCodec.readMap(payloadCaptor.getValue());
        assertThat(parsed.get("triggeredBy")).isEqualTo("manual-retry");
        assertThat(parsed.get("enqueuedAtMs")).isInstanceOf(Number.class);
    }

    @Test
    void requestAttributeExtractionThrowsWhenProductMissing() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commandService.requestAttributeExtraction(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
        verify(attributeOutboxRepository, never()).enqueue(anyLong(), anyString(), anyString());
    }

    private CatalogProductCreateRequest sampleItem(String suffix) {
        return new CatalogProductCreateRequest(
                "title-" + suffix,
                "brand",
                "category",
                "sub",
                new BigDecimal("1"),
                new BigDecimal("1"),
                new BigDecimal("2"),
                1,
                null,
                Map.of(),
                Map.of("product_id", "raw-" + suffix),
                List.of(new CatalogProductCreateRequest.SkuDraft(0, Map.of(), new BigDecimal("1"), 1, Map.of("sku_id", "sku-" + suffix))),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private CatalogProductRecord stubProduct(Long id) {
        return new CatalogProductRecord(
                id,
                "t",
                "brand",
                "category",
                "sub",
                new BigDecimal("1"),
                new BigDecimal("1"),
                new BigDecimal("2"),
                1,
                null,
                "ACTIVE",
                Map.of(),
                Map.of("product_id", "raw"),
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }
}
