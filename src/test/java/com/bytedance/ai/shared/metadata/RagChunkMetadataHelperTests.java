package com.bytedance.ai.shared.metadata;

import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RagChunkMetadataHelperTests {

    private final RagChunkMetadataHelper helper = new RagChunkMetadataHelper(
            new RagJsonCodec(JsonMapper.builder().build())
    );

    @Test
    void parsesJsonMetadata() {
        RagChunkMetadataView view = helper.parse("""
                {"blockType":"text","chunkType":"DESC","headingPath":["商品","描述"],"documentTags":["防晒"]}
                """);

        assertThat(view.blockType()).isEqualTo("text");
        assertThat(view.chunkType()).isEqualTo(RagChunkType.DESC);
        assertThat(view.headingPath()).containsExactly("商品", "描述");
        assertThat(view.documentTags()).containsExactly("防晒");
    }

    @Test
    void parsesLegacyJavaMapStringMetadata() {
        RagChunkMetadataView view = helper.parse("""
                {blockType=text, chunkType=DESC, sourceUri=catalog://spu/SPU-0009, headingPath=[纯物理防晒霜不含酒精敏感肌可用, 商品描述], headingPathText=纯物理防晒霜不含酒精敏感肌可用 / 商品描述, documentTags=[防晒, 敏感肌]}
                """);

        assertThat(view.blockType()).isEqualTo("text");
        assertThat(view.chunkType()).isEqualTo(RagChunkType.DESC);
        assertThat(view.headingPath()).containsExactly("纯物理防晒霜不含酒精敏感肌可用", "商品描述");
        assertThat(view.documentTags()).containsExactly("防晒", "敏感肌");
        assertThat(view.raw()).containsEntry("sourceUri", "catalog://spu/SPU-0009");
    }
}
