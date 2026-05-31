package com.bytedance.ai.indexing.application;

import com.bytedance.ai.document.spi.DocumentIndexingSpi;
import com.bytedance.ai.document.spi.DocumentIndexingView;
import com.bytedance.ai.indexing.model.*;
import com.bytedance.ai.indexing.persistence.*;
import com.bytedance.ai.indexing.service.RagIndexingFailureClassifier;
import com.bytedance.ai.indexing.service.RagIndexingMetrics;
import com.bytedance.ai.indexing.service.RagMilvusVectorIndexer;
import com.bytedance.ai.indexing.service.RagTextChunker;
import com.bytedance.ai.indexing.workflow.IndexWorkflowCommand;
import com.bytedance.ai.indexing.workflow.IndexWorkflowService;
import com.bytedance.ai.indexing.workflow.IndexWorkflowState;
import com.bytedance.ai.shared.model.RagDocumentStatus;
import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagLogFields;
import com.bytedance.ai.shared.support.RagLogHelper;
import io.milvus.v2.exception.ErrorCode;
import io.milvus.v2.exception.MilvusClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 负责将原始文档切片并写入 PostgreSQL / Milvus。
 */
@Service
public class RagIndexingService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexingService.class);

    private final DocumentIndexingSpi documentIndexingSpi;
    private final RagChunkRepository chunkRepository;
    private final RagTextChunker chunker;
    private final ObjectProvider<RagMilvusVectorIndexer> milvusVectorIndexerProvider;
    private final RagIndexJobRepository indexJobRepository;
    private final RagIndexOutboxRepository indexOutboxRepository;
    private final RagIndexJobTransitionRepository indexJobTransitionRepository;
    private final IndexWorkflowService workflowService;
    private final RagProperties ragProperties;
    private final RagIndexingFailureClassifier failureClassifier;
    private final RagIndexingMetrics indexingMetrics;
    private final com.bytedance.ai.shared.metadata.RagChunkTypeClassifier chunkTypeClassifier;

    public RagIndexingService(
            DocumentIndexingSpi documentIndexingSpi,
            RagChunkRepository chunkRepository,
            RagTextChunker chunker,
            ObjectProvider<RagMilvusVectorIndexer> milvusVectorIndexerProvider,
            RagIndexJobRepository indexJobRepository,
            RagIndexOutboxRepository indexOutboxRepository,
            RagIndexJobTransitionRepository indexJobTransitionRepository,
            IndexWorkflowService workflowService,
            RagProperties ragProperties,
            RagIndexingFailureClassifier failureClassifier,
            RagIndexingMetrics indexingMetrics,
            com.bytedance.ai.shared.metadata.RagChunkTypeClassifier chunkTypeClassifier
    ) {
        this.documentIndexingSpi = documentIndexingSpi;
        this.chunkRepository = chunkRepository;
        this.chunker = chunker;
        this.milvusVectorIndexerProvider = milvusVectorIndexerProvider;
        this.indexJobRepository = indexJobRepository;
        this.indexOutboxRepository = indexOutboxRepository;
        this.indexJobTransitionRepository = indexJobTransitionRepository;
        this.workflowService = workflowService;
        this.ragProperties = ragProperties;
        this.failureClassifier = failureClassifier;
        this.indexingMetrics = indexingMetrics;
        this.chunkTypeClassifier = chunkTypeClassifier;
    }

    /**
     * 使用指定工作流上下文执行一次索引尝试。
     */
    public void indexDocument(Long documentId, String expectedContentSha256, IndexWorkflowCommand workflowCommand) {
        DocumentIndexingView document = loadDocument(documentId);
        if (alreadyReachedTerminalState(documentId, expectedContentSha256, workflowCommand)) {
            return;
        }
        long startedAt = System.nanoTime();
        long targetGeneration = nextIndexGeneration(document);
        AtomicReference<IndexWorkflowState> currentState = new AtomicReference<>(IndexWorkflowState.DISPATCHING);
        AtomicBoolean vectorWriteApplied = new AtomicBoolean(false);
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.attempt.started")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_STARTED)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, document.contentSha256()))
                .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(document.contentSha256()))
                .addKeyValue(RagLogFields.RAG_INDEX_GENERATION, targetGeneration)
                .addKeyValue("rag.current_generation", document.indexedGeneration())
                .addKeyValue("rag.has_expected_version", expectedContentSha256 != null && !expectedContentSha256.isBlank())
                .addKeyValue(RagLogFields.RAG_TRIGGER_TYPE, workflowCommand.triggerType())
                .addKeyValue(RagLogFields.RAG_TRIGGERED_BY, workflowCommand.triggeredBy())
                .addKeyValue(RagLogFields.RAG_MESSAGE_ID, workflowCommand.messageId())
                .log(
                        "RAG indexing attempt started: documentId={}, contentSha={}, generation={}, triggerType={}, messageId={}",
                        documentId,
                        RagLogHelper.shortSha(document.contentSha256()),
                        targetGeneration,
                        workflowCommand.triggerType(),
                        workflowCommand.messageId()
                );

        try {
            validateIndexableDocument(document, expectedContentSha256, false);
            String currentContentSha256 = document.contentSha256();
            IndexWorkflowCommand attemptCommand = workflowCommand
                    .withTargetGeneration(targetGeneration)
                    .withMetadata("currentGeneration", document.indexedGeneration());
            workflowService.startAttempt(attemptCommand);
            currentState.set(IndexWorkflowState.PREPARING);
            int chunkCount = indexDocumentOnce(documentId, document, targetGeneration, attemptCommand, currentState, vectorWriteApplied);
            indexingMetrics.recordIndexSuccess(chunkCount, Duration.ofNanos(System.nanoTime() - startedAt));
            log.atInfo()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.completed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, currentContentSha256))
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(currentContentSha256))
                    .addKeyValue(RagLogFields.RAG_INDEX_GENERATION, targetGeneration)
                    .addKeyValue(RagLogFields.RAG_CHUNK_COUNT, chunkCount)
                    .addKeyValue(RagLogFields.RAG_ELAPSED_MS, Duration.ofNanos(System.nanoTime() - startedAt).toMillis())
                    .addKeyValue(RagLogFields.RAG_MESSAGE_ID, workflowCommand.messageId())
                    .log(
                            "RAG indexing attempt completed: documentId={}, contentSha={}, generation={}, chunkCount={}, elapsedMs={}",
                            documentId,
                            RagLogHelper.shortSha(currentContentSha256),
                            targetGeneration,
                            chunkCount,
                            Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
                    );
        } catch (SkippedIndexingException exception) {
            if (exception.requiresCleanup()) {
                cleanupFailedGenerationSafely(documentId, targetGeneration, document.indexedGeneration(), vectorWriteApplied.get());
            }
            if (exception.contentSha256() != null) {
                workflowService.skip(
                        workflowCommand
                                .withNote(exception.reason())
                                .withFailure("skipped", exception.reason())
                );
            }
            log.atInfo()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.skipped")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SKIPPED)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, exception.contentSha256()))
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(exception.contentSha256()))
                    .addKeyValue(RagLogFields.EVENT_REASON, exception.reason())
                    .addKeyValue("rag.requires_cleanup", exception.requiresCleanup())
                    .addKeyValue(RagLogFields.RAG_MESSAGE_ID, workflowCommand.messageId())
                    .log("RAG indexing skipped");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            RagIndexFailure failure = failureClassifier.classify(exception);
            String errorMessage = buildFailureMessage(exception, failure.reason());
            cleanupFailedGenerationSafely(documentId, targetGeneration, document.indexedGeneration(), vectorWriteApplied.get());

            if (failure.retryable()) {
                log.atWarn()
                        .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.failed")
                        .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_RETRY)
                        .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, document.contentSha256()))
                        .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                        .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(document.contentSha256()))
                        .addKeyValue(RagLogFields.RAG_INDEX_STAGE, currentState.get())
                        .addKeyValue(RagLogFields.EVENT_REASON, failure.reason())
                        .addKeyValue(RagLogFields.RAG_RETRYABLE, true)
                        .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                        .addKeyValue(RagLogFields.RAG_MESSAGE_ID, workflowCommand.messageId())
                        .log("RAG indexing failed with recoverable error");
                indexingMetrics.recordRetry(failure.reason());
            } else {
                log.atError()
                        .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.failed")
                        .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                        .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, document.contentSha256()))
                        .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                        .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(document.contentSha256()))
                        .addKeyValue(RagLogFields.RAG_INDEX_STAGE, currentState.get())
                        .addKeyValue(RagLogFields.EVENT_REASON, failure.reason())
                        .addKeyValue(RagLogFields.RAG_RETRYABLE, false)
                        .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                        .addKeyValue(RagLogFields.RAG_MESSAGE_ID, workflowCommand.messageId())
                        .setCause(exception)
                        .log("RAG indexing failed permanently");
                indexingMetrics.recordFailure(failure.reason(), false);
            }

            throw new RagIndexAttemptException(
                    toRagIndexStage(currentState.get()),
                    failure.retryable(),
                    failure.reason(),
                    errorMessage,
                    exception
            );
        }
    }

    private boolean alreadyReachedTerminalState(
            Long documentId,
            String expectedContentSha256,
            IndexWorkflowCommand workflowCommand
    ) {
        if (expectedContentSha256 == null || expectedContentSha256.isBlank()) {
            return false;
        }
        return indexJobRepository.findByDocumentIdAndContentSha256(documentId, expectedContentSha256)
                .filter(job -> RagIndexJobStatus.SUCCEEDED.name().equals(job.status())
                        || RagIndexJobStatus.FAILED.name().equals(job.status()))
                .map(job -> {
                    log.atInfo()
                            .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.message.stale_ignored")
                            .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SKIPPED)
                            .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, expectedContentSha256))
                            .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                            .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(expectedContentSha256))
                            .addKeyValue(RagLogFields.RAG_TRIGGER_TYPE, workflowCommand.triggerType())
                            .addKeyValue(RagLogFields.RAG_TRIGGERED_BY, workflowCommand.triggeredBy())
                            .addKeyValue(RagLogFields.RAG_MESSAGE_ID, workflowCommand.messageId())
                            .addKeyValue("rag.job_status", job.status())
                            .addKeyValue("rag.job_stage", job.stage())
                            .log("RAG index message ignored because workflow already reached terminal state");
                    return true;
                })
                .orElse(false);
    }

    /**
     * 删除指定文档已有的切片和向量索引，供删除文档或重建前清理使用。
     */
    public void deleteDocumentIndex(Long documentId) {
        deleteDocumentIndex(documentId, currentMilvusVectorIndexer());
    }

    /**
     * 清理文档已经不存在时，索引模块内部可能遗留的孤儿状态。
     */
    public void deleteOrphanedIndexingState(Long documentId) {
        deleteDocumentIndex(documentId);
        indexOutboxRepository.deleteByDocumentId(documentId);
        indexJobTransitionRepository.deleteByDocumentId(documentId);
        indexJobRepository.deleteByDocumentId(documentId);
    }

    /**
     * 物理清理特定代际的索引状态。
     * 由孤儿清理器调用，用于回收崩溃任务留下的残留数据。
     */
    public void deleteOrphanedIndexingState(Long documentId, long generation) {
        log.info("开始物理清理孤儿代际数据: documentId={}, generation={}", documentId, generation);

        // 1. 获取当前向量索引器
        RagMilvusVectorIndexer milvusVectorIndexer = currentMilvusVectorIndexer();

        // 2. 清理向量数据库 + 清理 PG 里的 Chunk 记录
        // 这里复用你已有的 deletePendingGeneration 方法
        // 第三个参数传 null，代表不保留任何东西，全量物理删除该代际
        deletePendingGeneration(documentId, generation, null, milvusVectorIndexer);

        log.debug("孤儿代际数据清理完成: documentId={}, generation={}", documentId, generation);
    }

    private int indexDocumentOnce(
            Long documentId,
            DocumentIndexingView document,
            long indexGeneration,
            IndexWorkflowCommand workflowCommand,
            AtomicReference<IndexWorkflowState> currentState,
            AtomicBoolean vectorWriteApplied
    ) {
        RagMilvusVectorIndexer milvusVectorIndexer = currentMilvusVectorIndexer();
        Long previousActiveGeneration = document.indexedGeneration();
        validateIndexableDocument(loadDocument(documentId), document.contentSha256(), false);
        cleanupDanglingGenerationsBeforeIndex(documentId, previousActiveGeneration, milvusVectorIndexer);
        deletePendingGeneration(documentId, indexGeneration, previousActiveGeneration, milvusVectorIndexer);

        workflowService.enterChunking(workflowCommand.withMetadata("phase", "chunking"));
        currentState.set(IndexWorkflowState.CHUNKING);
        List<String> documentTags = extractDocumentTags(document.metadata());
        List<RagChunkDraft> drafts = chunker.chunk(document.content()).stream()
                .map(chunk -> toDraft(document, documentTags, chunk, indexGeneration))
                .toList();
        if (drafts.isEmpty()) {
            throw new NonRetryableIndexingException("文档内容为空，无法建立索引");
        }
        log.debug(
                "RAG 文档切片完成。documentId={}, targetGeneration={}, chunkCount={}, documentTagCount={}",
                documentId,
                indexGeneration,
                drafts.size(),
                documentTags.size()
        );

        workflowService.enterSaveChunks(
                workflowCommand
                        .withMetadata("phase", "save_chunks")
                        .withMetadata("chunkDraftCount", drafts.size())
        );
        currentState.set(IndexWorkflowState.SAVE_CHUNKS);
        List<RagChunkRecord> savedChunks = chunkRepository.saveAll(documentId, drafts);
        int currentMaxChunkIndex = savedChunks.stream()
                .map(RagChunkRecord::chunkIndex)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1);
        log.debug(
                "RAG 切片保存完成。documentId={}, targetGeneration={}, chunkCount={}, maxChunkIndex={}",
                documentId,
                indexGeneration,
                savedChunks.size(),
                currentMaxChunkIndex
        );

        workflowService.enterVectorIndexing(
                workflowCommand
                        .withMetadata("phase", "vector_indexing")
                        .withChunkCount(savedChunks.size())
        );
        currentState.set(IndexWorkflowState.VECTOR_INDEXING);

        workflowService.enterCommitIndex(
                workflowCommand
                        .withMetadata("phase", "commit_index")
                        .withChunkCount(savedChunks.size())
        );
        currentState.set(IndexWorkflowState.COMMIT_INDEX);

        if (milvusVectorIndexer != null) {
            milvusVectorIndexer.add(document, savedChunks);
            vectorWriteApplied.set(true);
            log.debug(
                    "RAG 向量提交完成。documentId={}, targetGeneration={}, chunkCount={}, usedCustomMilvusIndexer={}",
                    documentId,
                    indexGeneration,
                    savedChunks.size(),
                    milvusVectorIndexer
            );
        } else {
            log.debug(
                    "RAG 向量提交跳过。documentId={}, targetGeneration={}, chunkCount={}, reason=milvus_disabled",
                    documentId,
                    indexGeneration,
                    savedChunks.size()
            );
        }

        validateIndexableDocument(loadDocument(documentId), document.contentSha256(), true);
        workflowService.succeed(
                workflowCommand
                        .withTargetGeneration(indexGeneration)
                        .withChunkCount(savedChunks.size())
                        .withMetadata("maxChunkIndex", currentMaxChunkIndex)
        );
        cleanupObsoleteGenerationsAfterCommit(documentId, indexGeneration, currentMaxChunkIndex, milvusVectorIndexer);
        return savedChunks.size();
    }

    private RagChunkDraft toDraft(
            DocumentIndexingView document,
            List<String> documentTags,
            RagTextChunk chunk,
            long indexGeneration
    ) {
        String sourceType = normalizeUpper(document.sourceType());
        com.bytedance.ai.shared.metadata.RagChunkType classifiedChunkType = chunkTypeClassifier.classify(
                document.sourceType(),
                chunk.chunkIndex(),
                chunk.headingPath(),
                chunk.blockMetadata()
        );
        String chunkType = com.bytedance.ai.indexing.service.ProductChunkTypeNormalizer
                .normalize(sourceType, chunk.headingPath(), classifiedChunkType);
        String headingPathText = toHeadingPathText(chunk.headingPath());
        Long productId = extractProductId(document.metadata());
        String vectorId = buildVectorId(document.id(), indexGeneration, chunk.chunkIndex());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", document.id());
        metadata.put("indexGeneration", indexGeneration);
        metadata.put("sourceUri", defaultString(document.sourceUri()));
        metadata.put("chunkIndex", chunk.chunkIndex());
        metadata.put("blockType", chunk.blockType());
        if (productId != null) {
            metadata.put("productId", productId);
        }
        if (documentTags != null && !documentTags.isEmpty()) {
            metadata.put("documentTags", new ArrayList<>(documentTags));
        }
        if (chunk.headingPath() != null && !chunk.headingPath().isEmpty()) {
            metadata.put("headingPath", new ArrayList<>(chunk.headingPath()));
        }
        if (chunk.codeLanguage() != null && !chunk.codeLanguage().isBlank()) {
            metadata.put("codeLanguage", chunk.codeLanguage());
        }
        if (chunk.blockMetadata() != null && !chunk.blockMetadata().isEmpty()) {
            metadata.putAll(chunk.blockMetadata());
        }
        return new RagChunkDraft(
                indexGeneration,
                productId,
                sourceType,
                chunk.chunkIndex(),
                chunkType,
                headingPathText,
                chunk.text(),
                chunk.hash(),
                chunk.charCount(),
                chunk.tokenCount(),
                vectorId,
                metadata
        );
    }

    private String buildVectorId(Long documentId, long indexGeneration, int chunkIndex) {
        return "rag-doc-" + documentId + "-gen-" + indexGeneration + "-chunk-" + chunkIndex;
    }

    private Long extractProductId(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object value = metadata.get("productId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }


    private String normalizeUpper(String value) {
        return value == null ? null : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private boolean isStaleVersion(DocumentIndexingView document, String expectedContentSha256) {
        return expectedContentSha256 != null
                && !expectedContentSha256.isBlank()
                && !expectedContentSha256.equals(document.contentSha256());
    }

    private RagIndexStage toRagIndexStage(IndexWorkflowState state) {
        return switch (state) {
            case DISPATCHING -> RagIndexStage.DISPATCHING;
            case PREPARING -> RagIndexStage.PREPARING;
            case CHUNKING -> RagIndexStage.CHUNKING;
            case SAVE_CHUNKS -> RagIndexStage.SAVE_CHUNKS;
            case VECTOR_INDEXING -> RagIndexStage.VECTOR_INDEXING;
            case COMMIT_INDEX -> RagIndexStage.COMMIT_INDEX;
            case COMPLETED -> RagIndexStage.COMPLETED;
            case SKIPPED -> RagIndexStage.SKIPPED;
            case NEW, QUEUED, FAILED -> RagIndexStage.QUEUED;
        };
    }

    private long nextIndexGeneration(DocumentIndexingView document) {
        long currentGeneration = document.indexedGeneration() == null ? 0L : document.indexedGeneration();
        long nowGeneration = System.currentTimeMillis();
        // 确保即使时钟回拨，新代际也一定比旧代际大
        return Math.max(nowGeneration, currentGeneration + 1L);
    }

    private String toHeadingPathText(List<String> headingPath) {
        return headingPath.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(segment -> !segment.isEmpty())
                .map(segment -> segment.toLowerCase(Locale.ROOT))
                .reduce((left, right) -> left + " / " + right)
                .orElse("");
    }

    private String abbreviate(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        if (message.length() <= 400) {
            return message;
        }
        return message.substring(0, 400) + "...";
    }

    private DocumentIndexingView loadDocument(Long documentId) {
        return documentIndexingSpi.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("RAG 文档不存在: " + documentId));
    }

    private List<String> extractDocumentTags(Map<String, Object> documentMetadata) {
        if (documentMetadata == null || documentMetadata.isEmpty()) {
            return List.of();
        }
        try {
            Map<String, Object> metadata = new LinkedHashMap<>(documentMetadata);
            if (metadata.isEmpty()) {
                return List.of();
            }

            Object frontmatter = metadata.get("frontmatter");
            if (frontmatter instanceof Map<?, ?> frontmatterMap) {
                List<String> tags = toStringList(frontmatterMap.get("tags"));
                if (!tags.isEmpty()) {
                    return tags;
                }
            }
            return toStringList(metadata.get("tags"));
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private String buildFailureMessage(Exception exception, String reason) {
        return "索引失败 [" + reason + "]: "
                + abbreviate(exception.getMessage());
    }

    private void deletePendingGeneration(
            Long documentId,
            long indexGeneration,
            Long preservedGeneration,
            RagMilvusVectorIndexer milvusVectorIndexer
    ) {
        if (preservedGeneration == null) {
            List<String> sameGenerationVectorIds = chunkRepository.findVectorIdsByDocumentIdAndGeneration(documentId, indexGeneration);
            log.debug(
                    "清理 pending generation 全量向量。documentId={}, generation={}, vectorCount={}",
                    documentId,
                    indexGeneration,
                    sameGenerationVectorIds.size()
            );
            deleteVectorIds(sameGenerationVectorIds, milvusVectorIndexer, "pending-generation");
        } else {
            List<String> sameGenerationVectorIds = chunkRepository.findVectorIdsByDocumentIdAndGeneration(documentId, indexGeneration);
            log.debug(
                    "清理 pending generation 向量。documentId={}, pendingGeneration={}, preservedGeneration={}, vectorCount={}",
                    documentId,
                    indexGeneration,
                    preservedGeneration,
                    sameGenerationVectorIds.size()
            );
            deleteVectorIds(sameGenerationVectorIds, milvusVectorIndexer, "pending-generation");
        }
        chunkRepository.deleteByDocumentIdAndGeneration(documentId, indexGeneration);
    }

    private void cleanupDanglingGenerationsBeforeIndex(
            Long documentId,
            Long activeGeneration,
            RagMilvusVectorIndexer milvusVectorIndexer
    ) {
        if (activeGeneration == null) {
            List<String> previousVectorIds = chunkRepository.findVectorIdsByDocumentId(documentId);
            log.debug(
                    "索引前清理历史向量。documentId={}, hasActiveGeneration=false, vectorCount={}",
                    documentId,
                    previousVectorIds.size()
            );
            deleteVectorIds(previousVectorIds, milvusVectorIndexer, "pre-index-all");
            chunkRepository.deleteByDocumentId(documentId);
            return;
        }

        List<String> danglingVectorIds = chunkRepository.findVectorIdsByDocumentIdExceptGeneration(documentId, activeGeneration);
        log.debug(
                "索引前清理非 active generation 向量。documentId={}, activeGeneration={}, vectorCount={}",
                documentId,
                activeGeneration,
                danglingVectorIds.size()
        );
        deleteVectorIds(danglingVectorIds, milvusVectorIndexer, "pre-index-non-active");
        chunkRepository.deleteByDocumentIdExceptGeneration(documentId, activeGeneration);
    }

    private void cleanupFailedGenerationSafely(
            Long documentId,
            long failedGeneration,
            Long previousActiveGeneration,
            boolean vectorWriteApplied
    ) {
        RagMilvusVectorIndexer milvusVectorIndexer = currentMilvusVectorIndexer();
        if (vectorWriteApplied && previousActiveGeneration != null) {
            log.info(
                    "检测到新向量已写入但索引提交失败，开始恢复 active generation。documentId={}, failedGeneration={}, previousActiveGeneration={}",
                    documentId,
                    failedGeneration,
                    previousActiveGeneration
            );
            restoreActiveGenerationVectors(documentId, previousActiveGeneration, milvusVectorIndexer);
        }
        deletePendingGeneration(
                documentId,
                failedGeneration,
                previousActiveGeneration,
                milvusVectorIndexer
        );
    }

    private void restoreActiveGenerationVectors(
            Long documentId,
            Long activeGeneration,
            RagMilvusVectorIndexer milvusVectorIndexer
    ) {
        List<RagChunkRecord> activeChunks = chunkRepository.findByDocumentIdAndGeneration(documentId, activeGeneration);
        if (activeChunks == null || activeChunks.isEmpty()) {
            log.warn(
                    "RAG active generation restore skipped because chunks are missing: documentId={}, activeGeneration={}",
                    documentId,
                    activeGeneration
            );
            return;
        }

        DocumentIndexingView document = documentIndexingSpi.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("恢复 active generation 向量失败，文档不存在: " + documentId));

        log.info(
                "RAG active generation restore started: documentId={}, activeGeneration={}, chunkCount={}",
                documentId,
                activeGeneration,
                activeChunks.size()
        );
        milvusVectorIndexer.add(document, activeChunks);
    }

    private void cleanupObsoleteGenerationsAfterCommit(
            Long documentId,
            long activeGeneration,
            int activeMaxChunkIndex,
            RagMilvusVectorIndexer milvusVectorIndexer
    ) {
        List<String> obsoleteVectorIds = chunkRepository.findVectorIdsByDocumentIdExceptGeneration(documentId, activeGeneration);
        try {
            log.debug(
                    "提交后清理旧 generation 向量。documentId={}, activeGeneration={}, activeMaxChunkIndex={}, vectorCount={}",
                    documentId,
                    activeGeneration,
                    activeMaxChunkIndex,
                    obsoleteVectorIds.size()
            );
            deleteVectorIds(obsoleteVectorIds, milvusVectorIndexer, "obsolete-generation");
        } catch (RuntimeException exception) {
            log.warn(
                    "旧 generation Milvus 向量清理失败，将由 recovery 补偿。documentId={}, activeGeneration={}, vectorCount={}, error={}",
                    documentId,
                    activeGeneration,
                    obsoleteVectorIds.size(),
                    RagLogHelper.errorSummary(exception)
            );
        }
        chunkRepository.deleteByDocumentIdExceptGeneration(documentId, activeGeneration);
    }

    private void deleteVectorIds(
            List<String> vectorIds,
            RagMilvusVectorIndexer milvusVectorIndexer,
            String phase
    ) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return;
        }
        List<String> normalizedVectorIds = vectorIds.stream()
                .filter(vectorId -> vectorId != null && !vectorId.isBlank())
                .distinct()
                .toList();
        if (normalizedVectorIds.isEmpty()) {
            return;
        }
        if (milvusVectorIndexer != null) {
            milvusVectorIndexer.delete(normalizedVectorIds);
            return;
        }
        if (!ragProperties.milvus().enabled()) {
            log.debug("Milvus disabled, skipping vector delete: phase={}, vectorCount={}", phase, normalizedVectorIds.size());
            return;
        }
        throw new MilvusClientException(
                ErrorCode.CLIENT_ERROR,
                "Milvus 向量删除器未装配，无法执行向量删除: phase=" + phase + ", vectorCount=" + normalizedVectorIds.size()
        );
    }

    private int normalizedNextChunkIndex(Integer maxChunkIndex) {
        return maxChunkIndex == null ? 0 : maxChunkIndex + 1;
    }

    private RagMilvusVectorIndexer currentMilvusVectorIndexer() {
        if (!ragProperties.milvus().enabled()) {
            return null;
        }
        RagMilvusVectorIndexer indexer = milvusVectorIndexerProvider.getIfAvailable();
        if (indexer == null) {
            throw new IllegalStateException("rag.milvus.enabled=true 但 RagMilvusVectorIndexer 未装配");
        }
        return indexer;
    }

    // 替换原有的 deleteDocumentIndex 方法
    private void deleteDocumentIndex(
            Long documentId,
            RagMilvusVectorIndexer milvusVectorIndexer
    ) {
        log.info("RAG document index cleanup started: documentId={}", documentId);

        if (milvusVectorIndexer != null) {
            milvusVectorIndexer.deleteByDocumentId(documentId);
            log.debug("Milvus vectors deleted by expression for documentId={}", documentId);
        } else {
            log.debug("Milvus disabled, skipping vector cleanup for documentId={}", documentId);
        }
        chunkRepository.deleteByDocumentId(documentId);
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.deleted")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                .log("RAG document index cleanup completed");
    }

    private void validateIndexableDocument(
            DocumentIndexingView document,
            String expectedContentSha256,
            boolean requiresCleanup
    ) {
        String jobContentSha256 = expectedContentSha256 != null && !expectedContentSha256.isBlank()
                ? expectedContentSha256
                : document.contentSha256();
        if (RagDocumentStatus.DELETING.name().equals(document.status())) {
            throw new SkippedIndexingException(jobContentSha256, "文档处于 DELETING，索引任务被跳过", requiresCleanup);
        }
        if (isStaleVersion(document, expectedContentSha256)) {
            throw new SkippedIndexingException(jobContentSha256, "文档版本已变化，旧索引任务被跳过", requiresCleanup);
        }
    }

    private static final class NonRetryableIndexingException extends RuntimeException {

        private NonRetryableIndexingException(String message) {
            super(message);
        }
    }

    private static final class SkippedIndexingException extends RuntimeException {

        private final String contentSha256;
        private final String reason;
        private final boolean requiresCleanup;

        private SkippedIndexingException(String contentSha256, String reason, boolean requiresCleanup) {
            super(reason);
            this.contentSha256 = contentSha256;
            this.reason = reason;
            this.requiresCleanup = requiresCleanup;
        }

        private String contentSha256() {
            return contentSha256;
        }

        private String reason() {
            return reason;
        }

        private boolean requiresCleanup() {
            return requiresCleanup;
        }
    }
}
