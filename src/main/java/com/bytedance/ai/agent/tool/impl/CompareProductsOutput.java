package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.SpuCardView;

import java.util.List;

public record CompareProductsOutput(
        String toolName,
        List<SpuCardView> cards,
        CompareMatrixView compareMatrix
) {
    public CompareProductsOutput {
        cards = cards == null ? List.of() : List.copyOf(cards);
    }
}
