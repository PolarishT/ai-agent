package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商品目录查询服务，负责组装商品、SKU 和内容视图。
 */
@Service
class CatalogQueryService implements CatalogQueryFacade {

    private final CatalogProductRepository productRepository;
    private final CatalogSkuRepository skuRepository;

    CatalogQueryService(CatalogProductRepository productRepository, CatalogSkuRepository skuRepository) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
    }

    @Override
    public CatalogProductView getProduct(Long productId) {
        CatalogProductRecord record = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("catalog_product 不存在: " + productId));
        List<CatalogSkuView> skuViews = skuRepository.findByProductId(productId).stream()
                .map(CatalogQueryService::toSkuView)
                .toList();
        return toProductView(record, skuViews);
    }

    @Override
    public List<CatalogProductView> searchActiveProducts(String keyword, int limit) {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 20);
        return productRepository.searchActiveByKeyword(keyword, safeLimit).stream()
                .map(record -> {
                    List<CatalogSkuView> skuViews = skuRepository.findByProductId(record.id()).stream()
                            .map(CatalogQueryService::toSkuView)
                            .toList();
                    return toProductView(record, skuViews);
                })
                .toList();
    }

    @Override
    public List<CatalogSkuView> listSkus(Long productId) {
        return skuRepository.findByProductId(productId).stream()
                .map(CatalogQueryService::toSkuView)
                .toList();
    }

    private static CatalogProductView toProductView(CatalogProductRecord record, List<CatalogSkuView> skus) {
        return new CatalogProductView(
                record.id(),
                record.title(),
                record.brand(),
                record.category(),
                record.subCategory(),
                record.basePrice(),
                record.priceMin(),
                record.priceMax(),
                record.totalStock(),
                record.imagePath(),
                record.status(),
                record.attributesJson(),
                record.rawJson(),
                skus,
                record.createdAt(),
                record.updatedAt()
        );
    }

    private static CatalogSkuView toSkuView(CatalogSkuRecord record) {
        return new CatalogSkuView(
                record.id(),
                String.valueOf(record.skuIndex()),
                record.propertiesJson(),
                record.price(),
                record.stock(),
                record.status()
        );
    }
}
