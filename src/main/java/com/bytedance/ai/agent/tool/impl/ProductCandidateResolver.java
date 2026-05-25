package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.retrieval.spi.ProductSearchHit;
import com.bytedance.ai.retrieval.spi.ProductSearchRequest;
import com.bytedance.ai.retrieval.spi.ProductSearchSpi;
import com.bytedance.ai.shared.metadata.RagSearchFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ProductCandidateResolver {

    private final CatalogQueryFacade catalogQueryFacade;
    private final ProductSearchSpi productSearchSpi;

    public ProductCandidateResolver(CatalogQueryFacade catalogQueryFacade, ProductSearchSpi productSearchSpi) {
        this.catalogQueryFacade = catalogQueryFacade;
        this.productSearchSpi = productSearchSpi;
    }

    public List<ResolvedProductCandidate> resolve(CompareProductsInput input, int topK) {
        LinkedHashMap<String, ResolvedProductCandidate> resolved = new LinkedHashMap<>();
        for (Long spuId : input.spuIds()) {
            resolveBySpuId(spuId).ifPresent(candidate -> putCandidate(resolved, candidate));
        }
        for (String externalRef : input.externalRefs()) {
            resolveByExternalRef(externalRef).ifPresent(candidate -> putCandidate(resolved, candidate));
        }
        for (String keyword : input.productKeywords()) {
            addSearchResults(resolved, keyword, topK);
            if (resolved.size() >= topK) {
                break;
            }
        }
        if (resolved.isEmpty()) {
            addSearchResults(resolved, input.query(), topK);
        }
        return resolved.values().stream().limit(topK).toList();
    }

    private Optional<ResolvedProductCandidate> resolveBySpuId(Long spuId) {
        if (spuId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ResolvedProductCandidate(catalogQueryFacade.getSpu(spuId), 1.0d, "用户明确指定商品"));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<ResolvedProductCandidate> resolveByExternalRef(String externalRef) {
        if (!StringUtils.hasText(externalRef)) {
            return Optional.empty();
        }
        return catalogQueryFacade.findSpuByExternalRef(externalRef.trim())
                .map(spu -> new ResolvedProductCandidate(spu, 1.0d, "用户明确指定商品"));
    }

    private void addSearchResults(Map<String, ResolvedProductCandidate> resolved, String query, int topK) {
        if (!StringUtils.hasText(query)) {
            return;
        }
        List<ProductSearchHit> hits = productSearchSpi.search(new ProductSearchRequest(
                query.trim(),
                RagSearchFilter.of("catalog://spu/", null, null),
                topK,
                List.of()
        ));
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (ProductSearchHit hit : hits) {
            resolveSpu(hit).ifPresent(spu -> putCandidate(
                    resolved,
                    new ResolvedProductCandidate(spu, hit.score(), hit.snippet())
            ));
            if (resolved.size() >= topK) {
                return;
            }
        }
    }

    private Optional<CatalogSpuView> resolveSpu(ProductSearchHit hit) {
        if (hit == null) {
            return Optional.empty();
        }
        if (hit.spuId() != null) {
            try {
                return Optional.of(catalogQueryFacade.getSpu(hit.spuId()));
            } catch (IllegalArgumentException ignored) {
                // 检索索引可能落后于 catalog，继续尝试 externalRef。
            }
        }
        if (StringUtils.hasText(hit.externalRef())) {
            return catalogQueryFacade.findSpuByExternalRef(hit.externalRef());
        }
        return Optional.empty();
    }

    private void putCandidate(Map<String, ResolvedProductCandidate> resolved, ResolvedProductCandidate candidate) {
        if (resolved.values().stream().anyMatch(existing -> sameProduct(existing.spu(), candidate.spu()))) {
            return;
        }
        String key = candidateKey(candidate.spu());
        resolved.putIfAbsent(key, candidate);
    }

    private boolean sameProduct(CatalogSpuView left, CatalogSpuView right) {
        if (StringUtils.hasText(left.externalRef()) && StringUtils.hasText(right.externalRef())) {
            return left.externalRef().equals(right.externalRef());
        }
        return left.id() != null && left.id().equals(right.id());
    }

    private String candidateKey(CatalogSpuView spu) {
        if (StringUtils.hasText(spu.externalRef())) {
            return "externalRef:" + spu.externalRef();
        }
        return "spuId:" + spu.id();
    }
}
