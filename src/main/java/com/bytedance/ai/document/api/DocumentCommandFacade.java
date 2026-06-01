package com.bytedance.ai.document.api;

/**
 * 文档写侧 Facade（其它模块依赖此契约，不直连 document 内部实现）。
 *
 * <p>该入口负责文档新增、整篇覆盖更新、重建索引和删除受理。新增/更新/重建会发布索引事件，
 * 后续由 indexing 模块异步完成切片与向量写入；删除会进入清理流程，异步清理向量与切片。
 */
public interface DocumentCommandFacade {

    /**
     * 创建一篇待索引文档，并触发异步索引。
     *
     * @param request 文档创建请求，正文通常为 Markdown 内容
     * @return 创建后的文档状态视图
     */
    RagDocumentView createDocument(RagDocumentCreateRequest request);

    /**
     * 整篇覆盖更新文档内容和元数据，并触发重新索引。
     *
     * @param documentId 文档主键
     * @param request    文档更新请求
     * @return 更新后的文档状态视图
     */
    RagDocumentView updateDocument(Long documentId, RagDocumentUpdateRequest request);

    /**
     * 对已有文档重新提交索引，不修改正文和元数据。
     *
     * @param documentId 文档主键
     * @return 重新提交后的文档状态视图
     */
    RagDocumentView reindexDocument(Long documentId);

    /**
     * 受理文档删除请求，并触发异步清理索引数据。
     *
     * @param documentId 文档主键
     */
    void deleteDocument(Long documentId);
}
