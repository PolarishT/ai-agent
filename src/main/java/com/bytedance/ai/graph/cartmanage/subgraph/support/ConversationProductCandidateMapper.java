package com.bytedance.ai.graph.cartmanage.subgraph.support;

import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;

import java.util.List;

/**
 * Converts central conversation product candidates into cart workflow candidates.
 */
public final class ConversationProductCandidateMapper {

    private ConversationProductCandidateMapper() {
    }

    public static ProductCandidate toCartCandidate(ConversationRuntimeContext.ProductCandidateItem candidate) {
        if (candidate == null) {
            return null;
        }
        return new ProductCandidate(
                candidate.productId(),
                candidate.skuId(),
                candidate.productName(),
                candidate.price(),
                candidate.brief(),
                candidate.spec(),
                candidate.externalRef() == null ? candidate.productId() : candidate.externalRef()
        );
    }

    public static List<ProductCandidate> toCartCandidates(
            List<ConversationRuntimeContext.ProductCandidateItem> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .map(ConversationProductCandidateMapper::toCartCandidate)
                .filter(candidate -> candidate != null && candidate.productId() != null)
                .toList();
    }
}
