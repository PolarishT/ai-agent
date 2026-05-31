package com.bytedance.ai.graph.catalog.persistence;

import java.util.List;
import java.util.Map;

public interface CatalogProductContentRepository {

    void saveKnowledge(Long productId, List<KnowledgeDraft> drafts);

    void saveFaqs(Long productId, List<FaqDraft> drafts);

    void saveReviews(Long productId, List<ReviewDraft> drafts);

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
}
