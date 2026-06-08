package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogProductReviewView;
import com.bytedance.ai.graph.catalog.api.CatalogProductSummaryView;
import com.bytedance.ai.graph.product.query.ProductHydrationOptions;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductReviewSnippet;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.shared.properties.RagProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * graph 侧二次排序：在 product retrieval 返回的 rankedHits 之上叠加 condition-specific 调整。
 *
 * <ul>
 *   <li>用 {@link CatalogQueryFacade} 把商品业务字段（price / brand / stock / attributes）补齐；</li>
 *   <li>{@code includeTerms} 命中 title / brand → 加权；</li>
 *   <li>{@code sort} 决定终态顺序：PRICE_ASC/DESC 按 price 排，RELEVANCE/RATING 按综合分排。</li>
 * </ul>
 *
 * <p>hydrate 使用 catalog 批量接口，避免 topK 放大成 N+1 JDBC 往返。
 */
@Component
public class ProductRanker {

    private static final Logger log = LoggerFactory.getLogger(ProductRanker.class);

    private final CatalogQueryFacade catalogQueryFacade;
    private final RagProperties ragProperties;

    public ProductRanker(CatalogQueryFacade catalogQueryFacade, RagProperties ragProperties) {
        this.catalogQueryFacade = catalogQueryFacade;
        this.ragProperties = ragProperties;
    }

    public List<ProductSearchCandidate> refine(
            List<ProductSearchHit> rankedHits,
            List<ProductSearchHit> keywordHits,
            List<ProductSearchHit> semanticHits,
            ProductQueryCondition condition
    ) {
        return refine(rankedHits, keywordHits, semanticHits, condition, ProductHydrationOptions.basic());
    }

    public List<ProductSearchCandidate> refine(
            List<ProductSearchHit> rankedHits,
            List<ProductSearchHit> keywordHits,
            List<ProductSearchHit> semanticHits,
            ProductQueryCondition condition,
            ProductHydrationOptions hydrationOptions
    ) {
        if (rankedHits == null || rankedHits.isEmpty()) {
            return List.of();
        }
        RagProperties.ProductQuery.RankWeights weights = ragProperties.productQuery().rankWeights();
        Map<String, Double> keywordScoreByKey = indexByKey(keywordHits);
        Map<String, Double> semanticScoreByKey = indexByKey(semanticHits);
        List<Long> productIds = productIds(rankedHits);
        Map<Long, CatalogProductSummaryView> productsById = lookupCatalog(productIds);
        Map<Long, List<ProductReviewSnippet>> reviewsByProductId = hydrateReviews(productIds, hydrationOptions);

        List<ProductSearchCandidate> enriched = new ArrayList<>(rankedHits.size());
        for (ProductSearchHit hit : rankedHits) {
            if (hit.productId() == null) {
                log.debug("Product hit dropped: productId missing externalRef={}", hit.externalRef());
                continue;
            }
            CatalogProductSummaryView view = productsById.get(hit.productId());
            if (view == null) {
                log.debug("Product hit dropped: catalog lookup empty for productId={} externalRef={}",
                        hit.productId(), hit.externalRef());
                continue;
            }
            BigDecimal price = pickDisplayPrice(view);
            List<String> matchReasons = new ArrayList<>();
            double conditionBoost = conditionBoost(view, condition, matchReasons, weights);
            double finalScore = hit.score() + conditionBoost;
            enriched.add(new ProductSearchCandidate(
                    view.id(),
                    String.valueOf(view.id()),
                    view.title(),
                    view.brand(),
                    view.category(),
                    view.subCategory(),
                    price,
                    view.totalStock(),
                    view.attributes() == null ? Map.of() : view.attributes(),
                    keywordScoreByKey.getOrDefault(key(hit), 0.0d),
                    semanticScoreByKey.getOrDefault(key(hit), 0.0d),
                    finalScore,
                    matchReasons,
                    reviewsByProductId.getOrDefault(view.id(), List.of())
            ));
        }
        return applySort(enriched, condition);
    }

    private List<Long> productIds(List<ProductSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
                .map(ProductSearchHit::productId)
                .filter(Objects::nonNull)
                .collect(LinkedHashSet<Long>::new, LinkedHashSet::add, LinkedHashSet::addAll)
                .stream()
                .toList();
    }

    private Map<Long, CatalogProductSummaryView> lookupCatalog(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CatalogProductSummaryView> result = new LinkedHashMap<>();
        for (CatalogProductSummaryView view : catalogQueryFacade.findProductSummaries(productIds)) {
            if (view.id() != null) {
                result.putIfAbsent(view.id(), view);
            }
        }
        return result;
    }

    private Map<Long, List<ProductReviewSnippet>> hydrateReviews(
            List<Long> productIds,
            ProductHydrationOptions hydrationOptions
    ) {
        if (productIds == null || productIds.isEmpty() || hydrationOptions == null
                || !hydrationOptions.includeReviews() || hydrationOptions.reviewLimit() <= 0) {
            return Map.of();
        }
        try {
            Map<Long, List<CatalogProductReviewView>> reviewsByProductId =
                    catalogQueryFacade.listReviewsByProductIds(productIds, hydrationOptions.reviewLimit());
            Map<Long, List<ProductReviewSnippet>> result = new LinkedHashMap<>();
            reviewsByProductId.forEach((productId, reviews) -> result.put(
                    productId,
                    reviews == null ? List.of() : reviews.stream().map(this::toReviewSnippet).toList()
            ));
            return result;
        } catch (RuntimeException exception) {
            log.warn("Product review batch hydrate failed: productCount={}, error={}",
                    productIds.size(), exception.toString());
            return Map.of();
        }
    }

    private ProductReviewSnippet toReviewSnippet(CatalogProductReviewView view) {
        return new ProductReviewSnippet(
                view.reviewIndex(),
                view.nickname(),
                view.rating(),
                view.sentiment(),
                view.content()
        );
    }

    private List<ProductSearchCandidate> applySort(List<ProductSearchCandidate> candidates, ProductQueryCondition condition) {
        String sort = condition == null || condition.sort() == null ? "RELEVANCE" : condition.sort();
        return switch (sort.toUpperCase(Locale.ROOT)) {
            case "PRICE_ASC" -> candidates.stream()
                    .sorted(Comparator.comparing(this::sortablePrice, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            case "PRICE_DESC" -> candidates.stream()
                    .sorted(Comparator.comparing(this::sortablePrice, Comparator.nullsFirst(Comparator.reverseOrder())))
                    .toList();
            case "RATING" -> candidates.stream()
                    .sorted(Comparator.comparing(this::ratingScore, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(Comparator.comparingDouble(ProductSearchCandidate::scoreFinal).reversed()))
                    .toList();
            default -> candidates.stream()
                    .sorted(Comparator.comparingDouble(ProductSearchCandidate::scoreFinal).reversed())
                    .toList();
        };
    }

    private BigDecimal sortablePrice(ProductSearchCandidate candidate) {
        return candidate.price();
    }

    private Double ratingScore(ProductSearchCandidate candidate) {
        Object raw = candidate.attributes().get("rating");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private double conditionBoost(
            CatalogProductSummaryView view,
            ProductQueryCondition condition,
            List<String> matchReasons,
            RagProperties.ProductQuery.RankWeights weights
    ) {
        if (condition == null) {
            return 0.0d;
        }
        double boost = 0.0d;
        if (!condition.includeTerms().isEmpty()) {
            String haystack = ((view.title() == null ? "" : view.title()) + " "
                    + (view.brand() == null ? "" : view.brand())).toLowerCase(Locale.ROOT);
            int matched = 0;
            for (String term : condition.includeTerms()) {
                if (term == null || term.isBlank()) {
                    continue;
                }
                if (haystack.contains(term.toLowerCase(Locale.ROOT))) {
                    matched++;
                    matchReasons.add("include_match=" + term);
                }
            }
            if (matched > 0) {
                boost += weights.conditionIncludeBoost() * Math.min(1.0d, matched / 3.0d);
            }
        }
        BigDecimal price = pickDisplayPrice(view);
        if (price != null && condition.priceMax() != null && price.compareTo(condition.priceMax()) <= 0) {
            matchReasons.add("price_match≤" + condition.priceMax().toPlainString());
            boost += weights.priceMatch() * 0.5d;
        }
        if (price != null && condition.priceMin() != null && price.compareTo(condition.priceMin()) >= 0) {
            matchReasons.add("price_match≥" + condition.priceMin().toPlainString());
            boost += weights.priceMatch() * 0.25d;
        }
        if (view.totalStock() != null && view.totalStock() > 0) {
            matchReasons.add("in_stock=" + view.totalStock());
        }
        // sort 模式与最终顺序解耦，但留一个固定 boost 让 ranker 输出可解释
        boost += weights.conditionSortBoost() * 0.0d;
        return boost;
    }

    private BigDecimal pickDisplayPrice(CatalogProductSummaryView view) {
        if (view.priceMin() != null) {
            return view.priceMin();
        }
        return view.priceMax();
    }

    private Map<String, Double> indexByKey(List<ProductSearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (ProductSearchHit hit : hits) {
            result.merge(key(hit), hit.score(), Math::max);
        }
        return result;
    }

    private String key(ProductSearchHit hit) {
        if (hit.productId() != null) {
            return "product:" + hit.productId();
        }
        if (hit.externalRef() != null) {
            return "ref:" + hit.externalRef();
        }
        return "doc:" + hit.documentId();
    }
}
