package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.persistence.CatalogProductRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogSkuRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogInventoryServiceTests {

    private final CatalogProductRepository productRepository = mock(CatalogProductRepository.class);
    private final CatalogSkuRepository skuRepository = mock(CatalogSkuRepository.class);
    private final CatalogInventoryService service = new CatalogInventoryService(productRepository, skuRepository);

    @Test
    void decreaseStockWithSkuDeductsSkuThenProduct() {
        when(skuRepository.decreaseStock(101L, 4L, 2)).thenReturn(true);
        when(productRepository.decreaseStock(101L, 2)).thenReturn(true);

        service.decreaseStock(101L, 4L, 2);

        InOrder order = inOrder(skuRepository, productRepository);
        order.verify(skuRepository).decreaseStock(101L, 4L, 2);
        order.verify(productRepository).decreaseStock(101L, 2);
    }

    @Test
    void decreaseStockDoesNotDeductProductWhenSkuStockFails() {
        when(skuRepository.decreaseStock(101L, 4L, 2)).thenReturn(false);

        assertThatThrownBy(() -> service.decreaseStock(101L, 4L, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SKU 库存不足");

        verify(productRepository, never()).decreaseStock(101L, 2);
    }

    @Test
    void decreaseStockThrowsWhenProductStockFailsAfterSkuDeduct() {
        when(skuRepository.decreaseStock(101L, 4L, 2)).thenReturn(true);
        when(productRepository.decreaseStock(101L, 2)).thenReturn(false);

        assertThatThrownBy(() -> service.decreaseStock(101L, 4L, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("库存不足");

        verify(skuRepository).decreaseStock(101L, 4L, 2);
        verify(productRepository).decreaseStock(101L, 2);
    }
}
