package com.bytedance.ai.shared.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagChunkTypeClassifierTests {

    private final RagChunkTypeClassifier classifier = new RagChunkTypeClassifier();

    @Test
    void missingBlockMetadataReturnsNullSoIndexingCanApplySourceTypeRule() {
        assertThat(classifier.classify("markdown", 0, List.of(), null)).isNull();
        assertThat(classifier.classify("PRODUCT_FAQ", 4, List.of("FAQ", "问题"), null))
                .as("分类器不再揣测 chunkType，由 RagIndexingService 按 sourceType + heading 归一")
                .isNull();
    }

    @Test
    void explicitChunkTypeInBlockMetadataIsHonored() {
        Map<String, Object> blockMetadata = Map.of("chunkType", "MARKETING");
        assertThat(classifier.classify("PRODUCT_PROFILE", 0, List.of(), blockMetadata))
                .isEqualTo(RagChunkType.MARKETING);
    }

    @Test
    void invalidExplicitChunkTypeReturnsNull() {
        Map<String, Object> blockMetadata = Map.of("chunkType", "无效类型");
        assertThat(classifier.classify("PRODUCT_PROFILE", 0, List.of(), blockMetadata)).isNull();
    }

    @Test
    void parseOrNullAcceptsLowerAndMixedCase() {
        assertThat(RagChunkType.parseOrNull("product_profile")).isEqualTo(RagChunkType.PRODUCT_PROFILE);
        assertThat(RagChunkType.parseOrNull(" Marketing ")).isEqualTo(RagChunkType.MARKETING);
        assertThat(RagChunkType.parseOrNull(null)).isNull();
        assertThat(RagChunkType.parseOrNull("")).isNull();
        assertThat(RagChunkType.parseOrNull("UNKNOWN")).isNull();
    }
}
