package com.bytedance.ai.indexing.service;

import com.bytedance.ai.document.spi.DocumentIndexingView;
import com.bytedance.ai.indexing.persistence.RagChunkRecord;
import com.bytedance.ai.shared.properties.RagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.UpsertParam;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagMilvusVectorIndexerTests {

    @Test
    @SuppressWarnings("unchecked")
    void upsertWritesChunkTextIntoMilvusContentField() {
        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        ObjectProvider<MilvusServiceClient> milvusProvider = (ObjectProvider<MilvusServiceClient>) mock(ObjectProvider.class);
        ObjectProvider<EmbeddingModel> embeddingProvider = (ObjectProvider<EmbeddingModel>) mock(ObjectProvider.class);
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProvider =
                (ObjectProvider<io.micrometer.core.instrument.MeterRegistry>) mock(ObjectProvider.class);
        when(milvusProvider.getIfAvailable()).thenReturn(milvusClient);
        when(embeddingProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyList())).thenReturn(List.of(
                new float[]{0.1f, 0.2f},
                new float[]{0.3f, 0.4f}
        ));
        when(milvusClient.upsert(any(UpsertParam.class))).thenReturn(R.success(MutationResult.getDefaultInstance()));

        RagMilvusVectorIndexer indexer = new RagMilvusVectorIndexer(
                milvusProvider,
                embeddingProvider,
                new RagIndexingMetrics(meterProvider),
                milvusEnabledProperties()
        );

        indexer.add(document(), List.of(
                chunk(1L, 0, "vec-1", "第一段商品介绍正文"),
                chunk(2L, 1, "vec-2", "第二段 FAQ 正文")
        ));

        ArgumentCaptor<UpsertParam> captor = ArgumentCaptor.forClass(UpsertParam.class);
        verify(milvusClient).upsert(captor.capture());
        InsertParam.Field contentField = captor.getValue().getFields().stream()
                .filter(field -> MilvusVectorStore.CONTENT_FIELD_NAME.equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(contentField.getValues()).isEqualTo(List.of("第一段商品介绍正文", "第二段 FAQ 正文"));

        InsertParam.Field docIdField = captor.getValue().getFields().stream()
                .filter(field -> MilvusVectorStore.DOC_ID_FIELD_NAME.equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(docIdField.getValues())
                .allSatisfy(value -> assertThat((String) value)
                        .startsWith("v_")
                        .hasSize(18));
        assertThat(docIdField.getValues()).isNotEqualTo(List.of("vec-1", "vec-2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteUsesSameShortMilvusDocIdMapping() {
        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        ObjectProvider<MilvusServiceClient> milvusProvider = (ObjectProvider<MilvusServiceClient>) mock(ObjectProvider.class);
        ObjectProvider<EmbeddingModel> embeddingProvider = (ObjectProvider<EmbeddingModel>) mock(ObjectProvider.class);
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProvider =
                (ObjectProvider<io.micrometer.core.instrument.MeterRegistry>) mock(ObjectProvider.class);
        when(milvusProvider.getIfAvailable()).thenReturn(milvusClient);
        when(milvusClient.delete(any(DeleteParam.class))).thenReturn(R.success(MutationResult.getDefaultInstance()));
        RagMilvusVectorIndexer indexer = new RagMilvusVectorIndexer(
                milvusProvider,
                embeddingProvider,
                new RagIndexingMetrics(meterProvider),
                milvusEnabledProperties()
        );

        indexer.delete(List.of("rag-doc-12345678901234567890-gen-9999999999-chunk-12345"));

        ArgumentCaptor<DeleteParam> captor = ArgumentCaptor.forClass(DeleteParam.class);
        verify(milvusClient).delete(captor.capture());
        assertThat(captor.getValue().getExpr())
                .startsWith(MilvusVectorStore.DOC_ID_FIELD_NAME + " in [\"v_")
                .doesNotContain("rag-doc-12345678901234567890-gen-9999999999-chunk-12345");
    }

    private static RagProperties milvusEnabledProperties() {
        return new RagProperties(
                3,
                400,
                80,
                "test-embedding",
                RagProperties.RocketMq.defaults(),
                new RagProperties.Milvus(
                        true,
                        "http://localhost:19530",
                        null,
                        null,
                        "rag_chunks",
                        2,
                        "COSINE",
                        0.2d,
                        RagProperties.Milvus.ProductSchema.defaults()
                ),
                RagProperties.Indexing.defaults(),
                RagProperties.Outbox.defaults(),
                RagProperties.Recovery.defaults(),
                RagProperties.Catalog.defaults(),
                RagProperties.ProductQuery.defaults()
        );
    }

    private static DocumentIndexingView document() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-01T18:20:00+10:00");
        return new DocumentIndexingView(
                10L,
                "PRODUCT_PROFILE",
                "product:10:profile",
                "10",
                "测试商品",
                "# 测试商品",
                "sha-document",
                null,
                "PENDING",
                0,
                0,
                Map.of(),
                null,
                null,
                null,
                now,
                now
        );
    }

    private static RagChunkRecord chunk(Long id, int chunkIndex, String vectorId, String text) {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-01T18:20:00+10:00");
        return new RagChunkRecord(
                id,
                10L,
                1L,
                100L,
                "PRODUCT_PROFILE",
                chunkIndex,
                chunkIndex == 0 ? "PRODUCT_PROFILE" : "FAQ_ANSWER",
                "测试商品",
                text,
                "hash-" + id,
                text.length(),
                null,
                vectorId,
                Map.of(),
                now,
                now
        );
    }
}
