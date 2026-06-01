package com.bytedance.ai.indexing.application;

import com.bytedance.ai.document.api.DocumentIndexCleanupRequestedEvent;
import com.bytedance.ai.document.api.DocumentIndexRequestedEvent;
import com.bytedance.ai.indexing.api.IndexingCommandFacade;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * document 模块事件到 indexing 写侧入口的桥接器。
 *
 * <p>监听器在事务提交前运行，使索引工作流和 outbox 行能够与文档落库处于同一个本地事务。
 */
@Component
class IndexingDocumentEventListener {

    private final IndexingCommandFacade indexingCommandFacade;

    IndexingDocumentEventListener(IndexingCommandFacade indexingCommandFacade) {
        this.indexingCommandFacade = indexingCommandFacade;
    }

    /**
     * 处理文档创建、更新、重建索引事件，提交对应文档版本的索引请求。
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onDocumentIndexRequested(DocumentIndexRequestedEvent event) {
        indexingCommandFacade.requestIndexing(
                event.documentId(),
                event.contentSha256(),
                event.triggeredBy()
        );
    }

    /**
     * 处理文档删除事件，清理尚未完成的索引投递状态。
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onDocumentIndexCleanupRequested(DocumentIndexCleanupRequestedEvent event) {
        indexingCommandFacade.cleanupPendingIndexing(event.documentId());
    }
}
