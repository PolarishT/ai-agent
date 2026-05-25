package com.bytedance.ai.agent.tool.impl;

import java.util.List;

public record CompareProductsInput(
        String query,
        List<Long> spuIds,
        List<String> externalRefs,
        List<String> productKeywords,
        Integer topK,
        List<String> compareAspects,
        String userGoal
) {
    public CompareProductsInput {
        spuIds = spuIds == null ? List.of() : List.copyOf(spuIds);
        externalRefs = externalRefs == null ? List.of() : List.copyOf(externalRefs);
        productKeywords = productKeywords == null ? List.of() : List.copyOf(productKeywords);
        compareAspects = compareAspects == null ? List.of() : List.copyOf(compareAspects);
    }
}
