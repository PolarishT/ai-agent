package com.bytedance.ai.indexing.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.bytedance.ai.document.spi.DocumentIndexingView;
import com.bytedance.ai.indexing.persistence.RagChunkRecord;
import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagLogHelper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.UpsertParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.EmbeddingUtils;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;

/**
 * 负责生成 embedding 并直接写入 Milvus。
 */
@Component
@ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "true")
public class RagMilvusVectorIndexer {

    private static final Logger log = LoggerFactory.getLogger(RagMilvusVectorIndexer.class);
    private static final int MAX_EMBEDDING_BATCH_SIZE = 10;

    private final ObjectProvider<MilvusServiceClient> milvusClientProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final RagIndexingMetrics indexingMetrics;
    private final RagProperties ragProperties;
    private final Gson gson = new Gson();

    public RagMilvusVectorIndexer(
            ObjectProvider<MilvusServiceClient> milvusClientProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            RagIndexingMetrics indexingMetrics,
            RagProperties ragProperties
    ) {
        this.milvusClientProvider = milvusClientProvider;
        this.embeddingModelProvider = embeddingModelProvider;
        this.indexingMetrics = indexingMetrics;
        this.ragProperties = ragProperties;
    }

    /**
     * 将当前 generation 的切片批量写入 Milvus。
     */
    public void add(DocumentIndexingView document, List<RagChunkRecord> chunks) {
        if (!ragProperties.milvus().enabled() || chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException(
                    "Milvus 写入前置条件不满足: milvusEnabled=" + ragProperties.milvus().enabled()
                            + ", chunkCount=" + (chunks == null ? 0 : chunks.size())
            );
        }

        MilvusServiceClient milvusClient = milvusClientProvider.getIfAvailable();
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (milvusClient == null || embeddingModel == null) {
            throw new IllegalStateException(
                    "Milvus 写入依赖缺失: hasMilvusClient=" + (milvusClient != null)
                            + ", hasEmbeddingModel=" + (embeddingModel != null)
            );
        }

        long writeStart = System.nanoTime();
        Map<String, float[]> embeddingsByVectorId = resolveEmbeddings(chunks, embeddingModel);
        log.debug(
                "Preparing Milvus upsert: documentId={}, contentSha={}, chunkCount={}, uniqueEmbeddingCount={}, collection={}, database={}",
                document.id(),
                RagLogHelper.shortSha(document.contentSha256()),
                chunks.size(),
                embeddingsByVectorId.size(),
                resolveCollectionName(),
                resolveDatabaseName()
        );

        List<String> ids = new ArrayList<>(chunks.size());
        List<String> contents = new ArrayList<>(chunks.size());
        List<JsonObject> metadatas = new ArrayList<>(chunks.size());
        List<List<Float>> embeddings = new ArrayList<>(chunks.size());

        for (RagChunkRecord chunk : chunks) {
            float[] vector = embeddingsByVectorId.get(chunk.vectorId());
            if (vector == null) {
                throw new IllegalStateException("未找到 chunk 的 embedding: " + chunk.vectorId());
            }

            ids.add(chunk.vectorId());
            contents.add("");
            metadatas.add(gson.toJsonTree(toMetadata(document, chunk)).getAsJsonObject());
            embeddings.add(EmbeddingUtils.toList(vector));
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(MilvusVectorStore.DOC_ID_FIELD_NAME, ids));
        fields.add(new InsertParam.Field(MilvusVectorStore.CONTENT_FIELD_NAME, contents));
        fields.add(new InsertParam.Field(MilvusVectorStore.METADATA_FIELD_NAME, metadatas));
        fields.add(new InsertParam.Field(MilvusVectorStore.EMBEDDING_FIELD_NAME, embeddings));

        UpsertParam.Builder upsertParamBuilder = UpsertParam.newBuilder()
                .withCollectionName(resolveCollectionName())
                .withFields(fields);

        String databaseName = resolveDatabaseName();
        if (StringUtils.hasText(databaseName)) {
            upsertParamBuilder.withDatabaseName(databaseName);
        }

        UpsertParam upsertParam = upsertParamBuilder.build();

        R<?> response = milvusClient.upsert(upsertParam);
        if (response.getException() != null) {
            throw new IllegalStateException("Milvus 向量写入失败", response.getException());
        }

        indexingMetrics.recordMilvusWrite(chunks.size(), Duration.ofNanos(System.nanoTime() - writeStart), true);
        log.info(
                "Milvus upsert completed: documentId={}, contentSha={}, chunkCount={}, collection={}",
                document.id(),
                RagLogHelper.shortSha(document.contentSha256()),
                chunks.size(),
                resolveCollectionName()
        );
    }

    /**
     * 按 vectorId 删除 Milvus 中的向量。
     */
    public void delete(List<String> vectorIds) {
        if (!ragProperties.milvus().enabled() || vectorIds == null || vectorIds.isEmpty()) {
            return;
        }

        MilvusServiceClient milvusClient = milvusClientProvider.getIfAvailable();
        if (milvusClient == null) {
            log.warn("Milvus delete skipped because MilvusServiceClient is unavailable: vectorCount={}", vectorIds.size());
            return;
        }

        DeleteParam.Builder deleteParamBuilder = DeleteParam.newBuilder()
                .withCollectionName(resolveCollectionName())
                .withExpr(buildDeleteExpression(vectorIds));
        log.debug(
                "准备执行 Milvus 删除。collection={}, database={}, vectorCount={}",
                resolveCollectionName(),
                resolveDatabaseName(),
                vectorIds.size()
        );

        String databaseName = resolveDatabaseName();
        if (StringUtils.hasText(databaseName)) {
            deleteParamBuilder.withDatabaseName(databaseName);
        }

        R<?> response = milvusClient.delete(deleteParamBuilder.build());
        if (response.getException() != null) {
            Exception exception = response.getException();
            if (isCollectionMissing(exception)) {
                log.warn(
                        "Milvus delete skipped because collection is missing: collection={}, database={}, vectorCount={}",
                        resolveCollectionName(),
                        databaseName,
                        vectorIds.size()
                );
                return;
            }
            throw new IllegalStateException("Milvus 向量删除失败", exception);
        }

        log.debug(
                "Milvus 向量删除完成。collection={}, database={}, vectorCount={}",
                resolveCollectionName(),
                databaseName,
                vectorIds.size()
        );
    }

    public void deleteByDocumentId(Long documentId) {
        MilvusServiceClient milvusClient = milvusClientProvider.getIfAvailable();
        // 构建 Milvus 的布尔表达式 (假设你在存入时 metadata 里的 key 叫 "documentId")
        String expr = "documentId == " + documentId;

        try {
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName("rag_chunks") // 你的集合名称
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = Objects.requireNonNull(milvusClient).delete(deleteParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Milvus 根据 documentId 删除失败: " + response.getMessage());
            }
        } catch (Exception e) {
            // 捕获特定异常并决定是否抛出
            log.error("Milvus 表达式删除异常, documentId={}, error={}", documentId, RagLogHelper.errorSummary(e), e);
            throw e;
        }
    }

    private Map<String, float[]> resolveEmbeddings(List<RagChunkRecord> chunks, EmbeddingModel embeddingModel) {
        Map<String, float[]> embeddingsByVectorId = new LinkedHashMap<>();
        for (int start = 0; start < chunks.size(); start += MAX_EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + MAX_EMBEDDING_BATCH_SIZE, chunks.size());
            List<RagChunkRecord> batch = chunks.subList(start, end);
            log.debug(
                    "Embedding 批次生成开始。batchStart={}, batchEnd={}, batchSize={}, totalMissingCount={}, model={}",
                    start,
                    end,
                    batch.size(),
                    chunks.size(),
                    ragProperties.embeddingModel()
            );
            List<float[]> batchEmbeddings = embeddingModel.embed(batch.stream()
                    .map(RagChunkRecord::chunkText)
                    .toList());
            if (batchEmbeddings.size() != batch.size()) {
                throw new IllegalStateException(
                        "Embedding 返回数量与请求数量不一致: requested=%d, actual=%d"
                                .formatted(batch.size(), batchEmbeddings.size())
                );
            }
            for (int index = 0; index < batch.size(); index++) {
                RagChunkRecord chunk = batch.get(index);
                float[] embedding = batchEmbeddings.get(index);
                embeddingsByVectorId.put(chunk.vectorId(), embedding);
            }
        }
        return embeddingsByVectorId;
    }

    private Map<String, Object> toMetadata(DocumentIndexingView document, RagChunkRecord chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("vectorId", chunk.vectorId());
        metadata.put("documentId", document.id());
        if (chunk.productId() != null) {
            metadata.put("productId", chunk.productId());
        }
        metadata.put("indexGeneration", chunk.indexGeneration());
        metadata.put("sourceType", chunk.sourceType());
        metadata.put("chunkType", chunk.chunkType());
        metadata.put("chunkIndex", chunk.chunkIndex());
        return metadata;
    }

    private String buildDeleteExpression(List<String> vectorIds) {
        String joinedIds = vectorIds.stream()
                .filter(StringUtils::hasText)
                .map(this::quoteStringLiteral)
                .reduce((left, right) -> left + "," + right)
                .orElseThrow(() -> new IllegalArgumentException("Milvus 删除缺少 vectorIds"));
        return MilvusVectorStore.DOC_ID_FIELD_NAME + " in [" + joinedIds + "]";
    }

    private String quoteStringLiteral(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private boolean isCollectionMissing(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message != null && message.toLowerCase().contains("collection not found");
    }

    private String resolveDatabaseName() {
        return StringUtils.hasText(ragProperties.milvus().databaseName())
                ? ragProperties.milvus().databaseName()
                : null;
    }

    private String resolveCollectionName() {
        return StringUtils.hasText(ragProperties.milvus().collectionName())
                ? ragProperties.milvus().collectionName()
                : MilvusVectorStore.DEFAULT_COLLECTION_NAME;
    }

    private int resolveEmbeddingDimension() {
        return ragProperties.milvus().embeddingDimension() > 0
                ? ragProperties.milvus().embeddingDimension()
                : MilvusVectorStore.OPENAI_EMBEDDING_DIMENSION_SIZE;
    }
}
