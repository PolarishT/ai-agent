package com.bytedance.ai.indexing.application;

import com.bytedance.ai.document.spi.DocumentIndexingSpi;
import com.bytedance.ai.indexing.api.IndexingCommandFacade;
import com.bytedance.ai.indexing.messaging.RagIndexEventPublisher;
import com.bytedance.ai.indexing.service.RagIndexingMetrics;
import com.bytedance.ai.indexing.workflow.IndexWorkflowCommand;
import com.bytedance.ai.indexing.workflow.IndexWorkflowService;
import com.bytedance.ai.indexing.workflow.IndexWorkflowTriggerType;
import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagLogFields;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;

/**
 * 索引写侧用例编排服务。
 *
 * <p>该服务接收 document 模块提交的索引/清理请求，负责创建工作流命令、
 * 按配置选择直接投递或 outbox 投递，并保证投递状态与工作流状态在本地事务内保持一致。
 */
@Service
class IndexingCommandService implements IndexingCommandFacade {

    private static final Logger log = LoggerFactory.getLogger(IndexingCommandService.class);

    private final ObjectProvider<RagIndexOutboxService> indexOutboxServiceProvider;
    private final RagIndexEventPublisher indexEventPublisher;
    private final IndexWorkflowService indexWorkflowService;
    private final RagIndexingService ragIndexingService;
    private final DocumentIndexingSpi documentIndexingSpi;
    private final RagProperties ragProperties;
    private final Executor ragVirtualThreadExecutor;
    private final RagIndexingMetrics indexingMetrics;

    IndexingCommandService(
            ObjectProvider<RagIndexOutboxService> indexOutboxServiceProvider,
            RagIndexEventPublisher indexEventPublisher,
            IndexWorkflowService indexWorkflowService,
            RagIndexingService ragIndexingService,
            DocumentIndexingSpi documentIndexingSpi,
            RagProperties ragProperties,
            @Qualifier("ragVirtualThreadExecutor") Executor ragVirtualThreadExecutor,
            RagIndexingMetrics indexingMetrics
    ) {
        this.indexOutboxServiceProvider = indexOutboxServiceProvider;
        this.indexEventPublisher = indexEventPublisher;
        this.indexWorkflowService = indexWorkflowService;
        this.ragIndexingService = ragIndexingService;
        this.documentIndexingSpi = documentIndexingSpi;
        this.ragProperties = ragProperties;
        this.ragVirtualThreadExecutor = ragVirtualThreadExecutor;
        this.indexingMetrics = indexingMetrics;
    }

    /**
     * 提交一次文档索引请求。
     *
     * <p>outbox 开启时，本方法只写入 outbox 并推进到 DISPATCHING，实际 MQ 投递由 dispatcher 完成；
     * outbox 关闭时，会在当前事务提交后直接发送索引事件。
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    @Override
    public void requestIndexing(Long documentId, String contentSha256, String triggeredBy) {
        IndexWorkflowCommand command = IndexWorkflowCommand.of(
                documentId,
                contentSha256,
                IndexWorkflowTriggerType.API,
                triggeredBy
        );

        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.dispatch.started")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_STARTED)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                .addKeyValue(RagLogFields.RAG_TRIGGER_TYPE, IndexWorkflowTriggerType.API)
                .addKeyValue(RagLogFields.RAG_TRIGGERED_BY, triggeredBy)
                .addKeyValue("rag.rocket_mq_enabled", ragProperties.rocketMq().enabled())
                .addKeyValue("rag.outbox_enabled", ragProperties.outbox().enabled())
                .log(
                        "RAG index dispatch started: documentId={}, contentSha={}, triggeredBy={}, outboxEnabled={}",
                        documentId,
                        RagLogHelper.shortSha(contentSha256),
                        triggeredBy,
                        ragProperties.outbox().enabled()
                );

        try {
            indexWorkflowService.queue(command);

            if (!isOutboxDispatchMode()) {
                indexWorkflowService.dispatch(command);
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            indexEventPublisher.publish(documentId, contentSha256);
                        }
                    });
                } else {
                    // 兜底逻辑：如果当前因为某种原因没在事务里，直接发
                    indexEventPublisher.publish(documentId, contentSha256);
                }
                return;
            }

            RagIndexOutboxService indexOutboxService = indexOutboxServiceProvider.getIfAvailable();
            if (indexOutboxService == null) {
                throw new IllegalStateException("rag.outbox.enabled=true 但 RagIndexOutboxService 未装配");
            }

            indexOutboxService.enqueue(documentId, contentSha256);
            // 只有确认 Outbox 行已经成功创建后，才把工作流推进到 DISPATCHING，
            // 避免 job/document 先进入 dispatch 状态，但实际尚未具备可投递载体。
            indexWorkflowService.dispatch(command);
        } catch (Exception exception) {
            String errorMessage = "索引任务投递失败: " + exception.getMessage();
            log.atError()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.dispatch.failed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                    .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                    .setCause(exception)
                    .log("RAG index dispatch failed");
            indexWorkflowService.fail(command.withFailure("dispatch", errorMessage));
            throw exception;
        }
    }

    /**
     * 清理文档删除前后的待投递索引状态。
     *
     * <p>消息模式下删除 outbox 中的待投递事件；直连模式下异步执行索引数据和文档记录清理。
     */
    @Override
    public void cleanupPendingIndexing(Long documentId) {
        if (!ragProperties.rocketMq().enabled() || !ragProperties.outbox().enabled()) {
            ragVirtualThreadExecutor.execute(() -> cleanupDirectIndexing(documentId));
            return;
        }

        RagIndexOutboxService outboxService = indexOutboxServiceProvider.getIfAvailable();
        if (outboxService != null) {
            outboxService.deleteByDocumentId(documentId);
        }
    }

    private boolean isOutboxDispatchMode() {
        return ragProperties.rocketMq().enabled() && ragProperties.outbox().enabled();
    }

    private void cleanupDirectIndexing(Long documentId) {
        try {
            ragIndexingService.deleteDocumentIndex(documentId);
            documentIndexingSpi.deleteById(documentId);
            indexingMetrics.recordDeleteCleanup("success");
            log.atInfo()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.cleanup.completed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .log("Direct RAG delete cleanup completed");
        } catch (IllegalArgumentException exception) {
            indexingMetrics.recordDeleteCleanup("skip");
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.cleanup.skipped")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SKIPPED)
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                    .log("Direct RAG delete cleanup skipped because document is unavailable");
        } catch (Exception exception) {
            indexingMetrics.recordDeleteCleanup("failure");
            log.atError()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.index.cleanup.failed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_ERROR_SUMMARY, RagLogHelper.errorSummary(exception))
                    .setCause(exception)
                    .log("Direct RAG delete cleanup failed");
        }
    }
}
