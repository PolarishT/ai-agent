package com.bytedance.ai.catalog.application;

import com.bytedance.ai.catalog.api.CatalogInventoryFacade;
import com.bytedance.ai.catalog.persistence.CatalogSpuRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogInventoryService implements CatalogInventoryFacade {

    private final CatalogSpuRepository spuRepository;

    CatalogInventoryService(CatalogSpuRepository spuRepository) {
        this.spuRepository = spuRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void decreaseStock(Long spuId, int quantity) {
        if (spuId == null || quantity <= 0) {
            throw new IllegalArgumentException("扣减库存参数非法");
        }
        if (!spuRepository.decreaseStock(spuId, quantity)) {
            throw new IllegalStateException("库存不足或商品已下架，无法下单");
        }
    }
}
