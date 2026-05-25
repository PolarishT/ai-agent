package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.SpuCardView;

import java.util.List;

public record PendingSelection(
        String type,
        String sourceNode,
        String resumeNode,
        List<SpuCardView> candidates,
        String selectedExternalRef,
        Long selectedSkuId
) {
    public PendingSelection {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public boolean resolved() {
        return selectedExternalRef != null && !selectedExternalRef.isBlank();
    }

    public PendingSelection withSelection(String externalRef, Long skuId) {
        return new PendingSelection(type, sourceNode, resumeNode, candidates, externalRef, skuId);
    }
}
