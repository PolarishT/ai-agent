package com.bytedance.ai.graph.product.retrieval;

import java.util.Map;

/**
 * 单条商品检索命中。
 *
 * <p>商品检索内部按 productId 聚合 chunk 后产出一条 Product 级 hit；
 * Product 的实时业务字段（price / stock）由调用方再从 catalog 取，**不**走 chunk metadata
 * （chunk metadata 在索引那一刻就被快照），避免给上层喂陈旧数据。
 *
 * @param productId   商品 id（{@code catalog_product.id}）
 * @param documentId  关联的 {@code rag_documents.id}
 * @param externalRef 已弃用语义：新 DDL 的 {@code catalog_product} 没有 external_ref 列，
 *                    retriever 会把本字段填成 {@code productId.toString()} 作 SPI 向后兼容；
 *                    新代码请直接读 {@link #productId()}，下个版本可考虑移除本字段。
 * @param score       综合排序分（0~1）
 * @param chunkType   触发该命中的最佳 {@code rag_chunks.chunk_type}；用于上层做加权解释
 * @param snippet     给 LLM 的简短上下文片段（来自命中 chunk 的正文）
 * @param metadata    额外原始 metadata（剩余字段透传，供上层观察）
 */
public record ProductSearchHit(
        Long productId,
        Long documentId,
        String externalRef,
        double score,
        String chunkType,
        String snippet,
        Map<String, Object> metadata
) {
}
