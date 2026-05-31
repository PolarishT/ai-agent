package com.bytedance.ai.shared.metadata;

import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Chunk 在 RAG 检索语义下的「角色分类」，作为 evidence 层的过滤维度。
 *
 * <p>这五个枚举值与 {@code rag_chunks.chunk_type} 列、Milvus metadata {@code chunkType} 字段
 * 一一对应，是 chunker / indexing / retrieval 链路上唯一允许出现的取值。任何写入 chunk_type
 * 的位置都必须使用 {@link #name()}，不要散落字符串字面量。
 *
 * <p>规约（与原始业务表的分工）：
 * <ul>
 *   <li>chunk 是 evidence，只承载相对稳定的可检索文本；</li>
 *   <li>库存、实时价格、SKU 状态、上下架状态等动态业务事实必须来自 {@code catalog_product} /
 *       {@code catalog_sku} 等原始表，不允许从 {@code chunk_text} 解析；</li>
 *   <li>{@link #PRODUCT_PROFILE} chunk 可携带商品标题、品牌、类目、卖点、规格说明等稳定字段。</li>
 * </ul>
 */
public enum RagChunkType {
    /** FAQ 问题切片，主要用于按"问题语义"召回相似 FAQ。 */
    FAQ_QUERY,
    /** FAQ 答案切片，主要在召回 FAQ_QUERY 后再 hydrate 原始 {@code catalog_product_faq.answer}。 */
    FAQ_ANSWER,
    /** 营销文案 / 卖点 / 商品知识 / 推荐理由 / 评价总结。 */
    MARKETING,
    /** 用户评论原文切片；最终展示仍以 {@code catalog_product_review} 原始记录为准。 */
    REVIEW,
    /** 商品基础资料切片（标题 / 品牌 / 类目 / 描述 / 规格 / 适用场景等稳定字段）。 */
    PRODUCT_PROFILE;

    /**
     * 大小写 + 空白容错的解析；遇到非法值或空值返回 {@code null}，让 caller 显式决定兜底。
     */
    public static RagChunkType parseOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return RagChunkType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
