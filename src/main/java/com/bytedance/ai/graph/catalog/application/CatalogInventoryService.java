package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogInventoryFacade;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品库存服务，封装库存校验和扣减查询能力。
 */
@Service
class CatalogInventoryService implements CatalogInventoryFacade {

    private final CatalogProductRepository productRepository;

    CatalogInventoryService(CatalogProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void decreaseStock(Long productId, int quantity) {
        if (productId == null || quantity <= 0) {
            throw new IllegalArgumentException("扣减库存参数非法");
        }
        if (!productRepository.decreaseStock(productId, quantity)) {
            throw new IllegalStateException("库存不足或商品已下架，无法下单");
        }
    }
}
