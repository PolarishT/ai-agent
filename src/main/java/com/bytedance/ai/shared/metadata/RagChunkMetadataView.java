package com.bytedance.ai.shared.metadata;

import java.util.List;
import java.util.Map;

/**
 * 统一承载 chunk metadata 中常用的结构化字段，避免各处重复解析 JSON。
 *
 * @param blockType 块类型，例如 text、table、code
 * @param chunkType chunk 在 RAG 语义下的角色分类，取 {@link RagChunkType} 五个值之一；
 *                  metadata 中未声明或非法时为 {@code null}
 * @param codeLanguage 代码块语言；非代码块时为空
 * @param headingPath 标题层级路径
 * @param documentTags 文档标签列表
 * @param raw 原始 metadata 映射
 */
public record RagChunkMetadataView(
        String blockType,
        RagChunkType chunkType,
        String codeLanguage,
        List<String> headingPath,
        List<String> documentTags,
        Map<String, Object> raw
) {
}
