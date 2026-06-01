package com.bytedance.ai.indexing.service;

import com.bytedance.ai.shared.metadata.RagChunkType;
import java.util.List;
import java.util.Locale;

/**
 * 把 document {@code sourceType} + chunk heading 归一到 {@link RagChunkType} 五个允许值之一。
 *
 * <p>规约：
 * <ul>
 *   <li>{@code PRODUCT_PROFILE} 文档 → {@link RagChunkType#PRODUCT_PROFILE}</li>
 *   <li>{@code PRODUCT_FAQ} 文档：heading 含「答案 / answer」→ {@link RagChunkType#FAQ_ANSWER}；
 *       含「问题 / question / query」→ {@link RagChunkType#FAQ_QUERY}；都不含时默认 {@link RagChunkType#FAQ_QUERY}</li>
 *   <li>{@code PRODUCT_REVIEW} 文档 → {@link RagChunkType#REVIEW}</li>
 *   <li>{@code PRODUCT_KNOWLEDGE} / {@code PRODUCT_REVIEW_SUMMARY} 文档 → {@link RagChunkType#MARKETING}</li>
 *   <li>非商品 sourceType 默认归 {@link RagChunkType#PRODUCT_PROFILE}，避免出现枚举外的脏值</li>
 * </ul>
 *
 * <p>{@code blockMetadata.chunkType}（{@code explicit}）若属于本枚举允许集合则优先生效，
 * 让上游 chunker 在特殊场景临时覆盖。
 */
public final class ProductChunkTypeNormalizer {

    private ProductChunkTypeNormalizer() {
    }

    /**
     * 根据文档来源、标题路径和显式 chunk 类型归一化 chunkType。
     *
     * @param sourceType  文档来源类型
     * @param headingPath 当前 chunk 所在 Markdown 标题路径
     * @param explicit    上游显式指定的 chunk 类型，存在时优先生效
     * @return {@link RagChunkType} 的枚举名
     */
    public static String normalize(String sourceType, List<String> headingPath, RagChunkType explicit) {
        if (explicit != null) {
            return explicit.name();
        }
        return switch (sourceType) {
            case "PRODUCT_PROFILE" -> RagChunkType.PRODUCT_PROFILE.name();
            case "PRODUCT_FAQ" -> classifyFaqChunk(headingPath).name();
            case "PRODUCT_REVIEW" -> RagChunkType.REVIEW.name();
            case "PRODUCT_REVIEW_SUMMARY", "PRODUCT_KNOWLEDGE" -> RagChunkType.MARKETING.name();
            case null, default -> RagChunkType.PRODUCT_PROFILE.name();
        };
    }

    private static RagChunkType classifyFaqChunk(List<String> headingPath) {
        if (headingPath != null) {
            for (String segment : headingPath) {
                if (segment == null) {
                    continue;
                }
                String lowered = segment.trim().toLowerCase(Locale.ROOT);
                if (lowered.contains("答案") || lowered.contains("answer")) {
                    return RagChunkType.FAQ_ANSWER;
                }
                if (lowered.contains("问题") || lowered.contains("question") || lowered.contains("query")) {
                    return RagChunkType.FAQ_QUERY;
                }
            }
        }
        return RagChunkType.FAQ_QUERY;
    }
}
