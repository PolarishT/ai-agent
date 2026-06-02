package com.bytedance.ai.graph.cartmanage.adapter;

import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.cartmanage.application.ProductCatalogResolver;
import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import com.bytedance.ai.graph.product.retrieval.ProductHardFilter;
import com.bytedance.ai.graph.product.retrieval.ProductSearchHit;
import com.bytedance.ai.graph.product.retrieval.ProductSearchRequest;
import com.bytedance.ai.graph.product.retrieval.ProductSearchResult;
import com.bytedance.ai.graph.product.retrieval.ProductSearchSpi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于商品检索能力的购物车商品候选解析器。
 */
@Service
public class ProductSearchCatalogResolver implements ProductCatalogResolver {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchCatalogResolver.class);

    private final CatalogQueryFacade catalogQueryFacade;
    private final ProductSearchSpi productSearchSpi;

    public ProductSearchCatalogResolver(CatalogQueryFacade catalogQueryFacade, ProductSearchSpi productSearchSpi) {
        this.catalogQueryFacade = catalogQueryFacade;
        this.productSearchSpi = productSearchSpi;
    }

    @Override
    public List<ProductCandidate> searchCandidates(String productName, int limit) {
        if (!StringUtils.hasText(productName)) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 20);
        ProductSearchResult result;
        try {
            result = productSearchSpi.searchProduct(new ProductSearchRequest(
                    productName,
                    safeLimit,
                    ProductHardFilter.empty(),
                    productName,
                    null,
                    ProductQueryIntent.PRODUCT_SEARCH
            ));
        } catch (RuntimeException exception) {
            log.warn("Cart fallback ProductSearchSpi failed: productName={}", productName, exception);
            return List.of();
        }
        List<ProductSearchHit> hits = result.rankedHits().isEmpty() ? result.fusedHits() : result.rankedHits();
        Set<Long> productIds = new LinkedHashSet<>();
        for (ProductSearchHit hit : hits) {
            if (hit.productId() != null) {
                productIds.add(hit.productId());
            }
            if (productIds.size() >= safeLimit) {
                break;
            }
        }

        List<ProductCandidate> candidates = new ArrayList<>();
        for (Long productId : productIds) {
            CatalogProductView product;
            try {
                product = catalogQueryFacade.getProduct(productId);
            } catch (RuntimeException exception) {
                log.debug("Cart fallback hit dropped: productId={} not found in catalog", productId);
                continue;
            }
            List<CatalogSkuView> skus = product.skus() == null ? List.of() : product.skus();
            if (skus.isEmpty()) {
                candidates.add(candidate(product, null));
            } else {
                for (CatalogSkuView sku : skus) {
                    if (!"ACTIVE".equals(sku.status())) {
                        continue;
                    }
                    candidates.add(candidate(product, sku));
                    if (candidates.size() >= safeLimit) {
                        return List.copyOf(candidates);
                    }
                }
            }
        }
        return List.copyOf(candidates);
    }

    private ProductCandidate candidate(CatalogProductView product, CatalogSkuView sku) {
        String productIdString = String.valueOf(product.id());
        return new ProductCandidate(
                productIdString,
                sku == null ? null : String.valueOf(sku.id()),
                product.title(),
                price(product, sku),
                brief(product),
                spec(sku),
                // catalog_product 已无 external_ref 列；保留字段并填 productId 作 SPI 向后兼容,
                // 让旧消费者（DefaultCandidateSelectionLlmService / normalizedCandidateText）
                // 不报 NPE。新代码应直接读 productId。
                productIdString
        );
    }

    private BigDecimal price(CatalogProductView product, CatalogSkuView sku) {
        if (sku != null && sku.price() != null) {
            return sku.price();
        }
        if (product.priceMin() != null) {
            return product.priceMin();
        }
        if (product.priceMax() != null) {
            return product.priceMax();
        }
        return product.basePrice();
    }

    private String brief(CatalogProductView product) {
        if (StringUtils.hasText(product.brand())) {
            return product.brand();
        }
        String category = product.category();
        String subCategory = product.subCategory();
        if (StringUtils.hasText(category) && StringUtils.hasText(subCategory)) {
            return category + "/" + subCategory;
        }
        return StringUtils.hasText(category) ? category : subCategory;
    }

    private String spec(CatalogSkuView sku) {
        if (sku == null || sku.specJson() == null || sku.specJson().isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Object> entry : sku.specJson().entrySet()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }
}
