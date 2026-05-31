package com.bytedance.ai.indexing.persistence;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * rag_chunks 表的记录模型。
 *
 * @param id 切片主键
 * @param documentId 所属文档主键
 * @param indexGeneration 所属索引 generation
 * @param productId 商品主键
 * @param sourceType 来源类型
 * @param chunkIndex 切片顺序号
 * @param chunkType 切片类型
 * @param headingPath 标题路径
 * @param chunkText 切片正文
 * @param chunkHash 切片内容哈希
 * @param charCount 字符数
 * @param tokenCount token 数
 * @param vectorId Milvus 向量 ID
 * @param metadata metadata
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record RagChunkRecord(
        Long id,
        Long documentId,
        Long indexGeneration,
        Long productId,
        String sourceType,
        Integer chunkIndex,
        String chunkType,
        String headingPath,
        String chunkText,
        String chunkHash,
        Integer charCount,
        Integer tokenCount,
        String vectorId,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
