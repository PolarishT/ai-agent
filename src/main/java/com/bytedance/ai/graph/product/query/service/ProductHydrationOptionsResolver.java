package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.ProductHydrationOptions;
import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import org.springframework.stereotype.Component;

/**
 * 按 {@link ProductQueryIntent} 决定 hydrate 范围。
 *
 * <p>目标是让 PRODUCT_RECOMMEND / PRODUCT_QA / REVIEW_QA 等不同子意图都能拿到「刚好够回答」的字段，
 * 既避免 hydrate 不足导致回答缺料，也避免无脑全字段导致 catalog 接口压力放大。
 *
 * <p>映射来自 spec：
 * <ul>
 *   <li>PRODUCT_RECOMMEND：SKU + description + chunks×2 + knowledge + reviews×2</li>
 *   <li>PRODUCT_QA：SKU + description + chunks×4 + faq + knowledge</li>
 *   <li>REVIEW_QA：SKU + description + chunks×2 + reviews×5</li>
 *   <li>INVENTORY_CHECK：仅 SKU</li>
 *   <li>其它：basic（仅 product 主表）</li>
 * </ul>
 */
@Component
public class ProductHydrationOptionsResolver {

    public ProductHydrationOptions optionsFor(ProductQueryIntent intent) {
        if (intent == null) {
            return ProductHydrationOptions.basic();
        }
        return switch (intent) {
            case PRODUCT_RECOMMEND -> new ProductHydrationOptions(
                    true,
                    true,
                    true,
                    false,
                    true,
                    true,
                    2,
                    2
            );
            case PRODUCT_COMPARE -> new ProductHydrationOptions(
                    true,
                    true,
                    true,
                    false,
                    true,
                    true,
                    3,
                    2
            );
            case PRODUCT_QA -> new ProductHydrationOptions(
                    true,
                    true,
                    true,
                    true,
                    true,
                    false,
                    4,
                    0
            );
            case REVIEW_QA -> new ProductHydrationOptions(
                    true,
                    true,
                    true,
                    false,
                    false,
                    true,
                    2,
                    5
            );
            case INVENTORY_CHECK -> new ProductHydrationOptions(
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    0,
                    0
            );
            default -> ProductHydrationOptions.basic();
        };
    }
}
