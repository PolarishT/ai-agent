package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.CompareMatrixView;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class CompareProductsService {

    private static final int MIN_TOP_K = 2;
    private static final int MAX_TOP_K = 5;
    private static final int DEFAULT_TOP_K = 3;

    private final ProductCandidateResolver candidateResolver;
    private final CompareMatrixBuilder matrixBuilder;

    public CompareProductsService(ProductCandidateResolver candidateResolver, CompareMatrixBuilder matrixBuilder) {
        this.candidateResolver = candidateResolver;
        this.matrixBuilder = matrixBuilder;
    }

    public CompareProductsOutput compare(CompareProductsInput input) {
        if (input == null || !StringUtils.hasText(input.query())) {
            throw new IllegalArgumentException("compare_products.query 不能为空");
        }
        int topK = normalizeTopK(input.topK());
        List<ResolvedProductCandidate> candidates = candidateResolver.resolve(input, topK);
        List<SpuCardView> cards = toCards(candidates);
        CompareMatrixView matrix = candidates.size() < MIN_TOP_K
                ? null
                : matrixBuilder.build(cards, candidates, input.compareAspects(), input.userGoal());
        return new CompareProductsOutput(CompareProductsToolCallback.TOOL_NAME, cards, matrix);
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.max(MIN_TOP_K, Math.min(topK, MAX_TOP_K));
    }

    private List<SpuCardView> toCards(List<ResolvedProductCandidate> candidates) {
        List<SpuCardView> cards = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            ResolvedProductCandidate candidate = candidates.get(i);
            CatalogSpuView spu = candidate.spu();
            cards.add(new SpuCardView(
                    spu.id(),
                    spu.externalRef(),
                    spu.title(),
                    spu.brand(),
                    firstImage(spu.images()),
                    spu.priceMin(),
                    spu.priceMax(),
                    spu.stock(),
                    candidate.score(),
                    List.of(),
                    StringUtils.hasText(candidate.reason()) ? List.of(candidate.reason()) : List.of(),
                    "#" + (i + 1)
            ));
        }
        return cards;
    }

    private String firstImage(List<String> images) {
        return images == null || images.isEmpty() ? null : images.getFirst();
    }
}
