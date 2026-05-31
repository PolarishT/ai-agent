package com.bytedance.ai.indexing.model;

import java.util.Map;

/**
 * 尚未持久化的文档切片。
 *
 * @param indexGeneration 本次索引生成号
 * @param productId 商品主键
 * @param sourceType 文档来源类型
 * @param chunkIndex 切片顺序号
 * @param chunkType 切片类型
 * @param headingPath Markdown 标题路径
 * @param chunkText 切片正文
 * @param chunkHash 切片内容哈希
 * @param charCount 切片字符数
 * @param tokenCount 切片 token 数；未知时可为空
 * @param vectorId 对应向量 ID；未写入时可为空
 * @param metadata 切片 metadata
 */
public record RagChunkDraft(
        long indexGeneration,
        Long productId,
        String sourceType,
        int chunkIndex,
        String chunkType,
        String headingPath,
        String chunkText,
        String chunkHash,
        int charCount,
        Integer tokenCount,
        String vectorId,
        Map<String, Object> metadata
) {
}
