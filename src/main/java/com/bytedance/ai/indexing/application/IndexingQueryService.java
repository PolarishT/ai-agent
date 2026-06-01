package com.bytedance.ai.indexing.application;

import com.bytedance.ai.document.spi.DocumentIndexingSpi;
import com.bytedance.ai.document.spi.DocumentIndexingView;
import com.bytedance.ai.indexing.api.IndexingQueryFacade;
import com.bytedance.ai.indexing.api.RagIndexJobView;
import com.bytedance.ai.indexing.api.RagIndexTimelineView;
import com.bytedance.ai.indexing.persistence.RagIndexJobRecord;
import com.bytedance.ai.indexing.persistence.RagIndexJobRepository;
import org.springframework.stereotype.Service;

/**
 * 索引读侧用例编排服务。
 *
 * <p>对 controller 暴露当前文档版本的索引任务与时间线视图，内部通过 document SPI
 * 读取文档快照，再聚合 indexing 自身的 job / outbox / transition 状态。
 */
@Service
class IndexingQueryService implements IndexingQueryFacade {

    private final DocumentIndexingSpi documentIndexingSpi;
    private final RagIndexJobRepository indexJobRepository;
    private final RagIndexTimelineService indexTimelineService;

    IndexingQueryService(
            DocumentIndexingSpi documentIndexingSpi,
            RagIndexJobRepository indexJobRepository,
            RagIndexTimelineService indexTimelineService
    ) {
        this.documentIndexingSpi = documentIndexingSpi;
        this.indexJobRepository = indexJobRepository;
        this.indexTimelineService = indexTimelineService;
    }

    /**
     * 查询当前文档内容版本对应的索引任务。
     *
     * @param documentId 文档主键
     * @return 当前内容版本的索引任务视图；尚未创建任务时返回 null
     */
    @Override
    public RagIndexJobView getIndexJob(Long documentId) {
        DocumentIndexingView document = documentIndexingSpi.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("RAG 文档不存在: " + documentId));
        RagIndexJobRecord job = indexJobRepository.findByDocumentIdAndContentSha256(documentId, document.contentSha256())
                .orElse(null);
        if (job != null) {
            return new RagIndexJobView(
                    job.id(),
                    job.documentId(),
                    job.contentSha256(),
                    job.status(),
                    job.stage(),
                    job.version(),
                    job.lastEvent(),
                    job.attemptCount(),
                    job.targetGeneration(),
                    job.messageId(),
                    job.lastError(),
                    job.startedAt(),
                    job.finishedAt(),
                    job.createdAt(),
                    job.updatedAt()
            );
        }
        return null;
    }

    /**
     * 查询文档当前内容版本的完整索引时间线。
     *
     * @param documentId 文档主键
     * @return 聚合后的索引时间线视图
     */
    @Override
    public RagIndexTimelineView getIndexTimeline(Long documentId) {
        return indexTimelineService.getTimeline(documentId);
    }
}
