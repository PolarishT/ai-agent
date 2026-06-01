package com.bytedance.ai.graph.cartmanage.adapter;

import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.cartmanage.application.ProductCatalogResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ProductSearchCatalogResolver implements ProductCatalogResolver {

    private final CatalogQueryFacade catalogQueryFacade;

    public ProductSearchCatalogResolver(CatalogQueryFacade catalogQueryFacade) {
        this.catalogQueryFacade = catalogQueryFacade;
    }

    @Override
    public List<ProductCandidate> searchCandidates(String productName, int limit) {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 20);
        List<ProductCandidate> candidates = new ArrayList<>();
        for (CatalogProductView product : catalogQueryFacade.searchActiveProducts(productName, safeLimit)) {
            List<CatalogSkuView> skus = product.skus() == null ? List.of() : product.skus();
            if (skus.isEmpty()) {
                candidates.add(candidate(product, null));
            } else {
                for (CatalogSkuView sku : skus) {
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
