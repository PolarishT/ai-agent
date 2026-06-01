package com.bytedance.ai.indexing.api;

/**
 * 对外暴露的索引写侧入口。
 */
public interface IndexingCommandFacade {

    /**
     * 提交指定文档版本的索引请求。
     *
     * <p>调用方需要传入当前文档内容的 sha256，用于后续消费消息时识别旧版本消息。
     * 实现会根据配置选择直接投递或写入 outbox，并推进索引工作流状态。
     *
     * @param documentId       文档主键
     * @param contentSha256    当前文档内容 sha256
     * @param triggeredBy      触发方标识，用于审计和排障
     */
    void requestIndexing(Long documentId, String contentSha256, String triggeredBy);

    /**
     * 清理指定文档尚未完成的索引投递状态。
     *
     * <p>删除文档时调用该方法，RocketMQ/outbox 模式下会删除待投递事件；
     * 直连模式下会异步清理切片、向量与文档记录。
     *
     * @param documentId 文档主键
     */
    void cleanupPendingIndexing(Long documentId);
}
