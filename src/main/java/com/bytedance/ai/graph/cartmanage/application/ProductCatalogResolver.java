package com.bytedance.ai.graph.cartmanage.application;

import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import java.util.List;

/**
 * 购物车流程使用的商品候选解析端口。
 */
public interface ProductCatalogResolver {

    List<ProductCandidate> searchCandidates(String productName, int limit);

    static ProductCatalogResolver empty() {
        return (productName, limit) -> List.of();
    }
}
