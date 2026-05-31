package com.bytedance.ai.shared.metadata;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * Block 级 chunkType 提取器：仅消费 {@code blockMetadata.chunkType} 中的显式声明。
 *
 * <p>主链路的 chunkType 归一交由 {@code RagIndexingService.normalizeChunkType(...)} 完成，
 * 它会按文档 {@code source_type} + heading 路径决定最终落库的取值。这里只是给上游
 * chunker 一个「显式标注覆盖」的口子：如果某个块在自己的 metadata 里显式写了
 * {@code chunkType=PRODUCT_PROFILE/MARKETING/FAQ_QUERY/FAQ_ANSWER/REVIEW}，indexing 层应该尊重。
 *
 * <p>未显式声明或值非法时返回 {@code null}，由 indexing 层用归一规则兜底；
 * 不要在这里硬塞默认值——避免「分类器自作主张」与 indexing 归一规则冲突。
 */
@Component
public class RagChunkTypeClassifier {

    /**
     * @param sourceType    rag_documents.source_type（仅作为上下文参数，目前未用于分类）
     * @param chunkIndex    切片顺序号（0-based，目前未用于分类）
     * @param headingPath   切片所在的 markdown heading 层级（目前未用于分类）
     * @param blockMetadata 切片块级 metadata；若上游显式塞了 chunkType 则采纳
     * @return 显式声明的 {@link RagChunkType}；缺失或非法时返回 {@code null}
     */
    public RagChunkType classify(
            String sourceType,
            int chunkIndex,
            List<String> headingPath,
            Map<String, Object> blockMetadata
    ) {
        if (blockMetadata == null || blockMetadata.isEmpty()) {
            return null;
        }
        Object value = blockMetadata.get("chunkType");
        if (value == null) {
            return null;
        }
        return RagChunkType.parseOrNull(String.valueOf(value));
    }
}
