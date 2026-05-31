package com.bytedance.ai.indexing.persistence;

import com.bytedance.ai.indexing.model.RagChunkDraft;
import java.util.List;

/**
 * 文档切片仓储。
 */
public interface RagChunkRepository {

    /**
     * 批量保存同一文档、同一批 generation 生成出的切片记录。
     */
    List<RagChunkRecord> saveAll(Long documentId, List<RagChunkDraft> chunks);

    /**
     * 查询文档名下所有已落库的向量 ID。
     */
    List<String> findVectorIdsByDocumentId(Long documentId);

    /**
     * 查询某个 generation 对应的向量 ID。
     */
    List<String> findVectorIdsByDocumentIdAndGeneration(Long documentId, Long indexGeneration);

    /**
     * 查询某个 generation 的完整切片记录，通常用于恢复 active generation 的向量内容。
     */
    List<RagChunkRecord> findByDocumentIdAndGeneration(Long documentId, Long indexGeneration);

    /**
     * 查询除当前 generation 之外的旧向量 ID，通常用于清理历史版本。
     */
    List<String> findVectorIdsByDocumentIdExceptGeneration(Long documentId, Long indexGeneration);

    /**
     * 查询某个 generation 中最大的 chunk 序号，用于判断稳定向量 ID 的尾部删除范围。
     */
    Integer findMaxChunkIndexByDocumentIdAndGeneration(Long documentId, Long indexGeneration);

    /**
     * 查询除当前 generation 外最大的 chunk 序号，用于清理历史版本遗留的尾部向量。
     */
    Integer findMaxChunkIndexByDocumentIdExceptGeneration(Long documentId, Long indexGeneration);

    /**
     * 删除文档下全部切片。
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 删除文档某个 generation 的切片。
     */
    void deleteByDocumentIdAndGeneration(Long documentId, Long indexGeneration);

    /**
     * 删除文档除当前 generation 外的所有历史切片。
     */
    void deleteByDocumentIdExceptGeneration(Long documentId, Long indexGeneration);

}
