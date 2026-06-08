package com.bytedance.ai.graph.catalog.persistence;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 商品目录仓储端口，隔离领域逻辑与底层持久化实现。
 */
public interface CatalogProductContentRepository {

    void saveKnowledge(Long productId, List<KnowledgeDraft> drafts);

    void saveFaqs(Long productId, List<FaqDraft> drafts);

    void saveReviews(Long productId, List<ReviewDraft> drafts);

    List<ReviewRecord> findReviewsByProductIds(Collection<Long> productIds, int limitPerProduct);

    record KnowledgeDraft(
            String knowledgeType,
            String title,
            String content,
            String contentSha256,
            Map<String, Object> metadata
    ) {
    }

    record FaqDraft(
            int faqIndex,
            String question,
            String answer,
            String contentSha256,
            Map<String, Object> metadata
    ) {
    }

    record ReviewDraft(
            int reviewIndex,
            String nickname,
            Integer rating,
            String content,
            String contentSha256,
            String sentiment,
            Map<String, Object> metadata
    ) {
    }

    record ReviewRecord(
            Long id,
            Long productId,
            Integer reviewIndex,
            String nickname,
            Integer rating,
            String content,
            String sentiment,
            Map<String, Object> metadata,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public ReviewRecord {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
