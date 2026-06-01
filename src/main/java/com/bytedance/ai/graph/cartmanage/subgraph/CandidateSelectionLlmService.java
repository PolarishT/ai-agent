package com.bytedance.ai.graph.cartmanage.subgraph;

import com.bytedance.ai.graph.cartmanage.ProductCandidate;

import java.util.List;
import java.util.Optional;

/**
 * 候选商品选择端口。
 */
public interface CandidateSelectionLlmService {

    Optional<Integer> resolveIndex(String userMessage, List<ProductCandidate> candidates);
}
