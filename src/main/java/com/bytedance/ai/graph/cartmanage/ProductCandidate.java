package com.bytedance.ai.graph.cartmanage;

import java.math.BigDecimal;

/**
 * cart_manage_workflow 的商品候选条目；由 {@code ProductCatalogResolver} 生成，
 * 持久化到中央 {@code agent_context_items} 让下一轮"我要第 N 个"能解析。
 *
 * @param productId   商品主键字符串（{@code catalog_product.id}）
 * @param skuId       SKU 主键字符串（{@code catalog_sku.id}），无 SKU 时为 null
 * @param productName 商品名称
 * @param price       展示价格
 * @param brief       简要信息（品牌优先，否则类目）
 * @param spec        规格摘要（"颜色=黑色, 容量=500ml"）
 * @param externalRef <b>已弃用语义</b>：新 DDL 的 {@code catalog_product} 没有 external_ref 列，
 *                    resolver 把本字段填成 {@code productId.toString()} 作向后兼容；
 *                    {@code DefaultCandidateSelectionLlmService} / {@code normalizedCandidateText}
 *                    等老消费者拿到的就是冗余的 productId 字符串，下个版本可考虑移除本字段。
 */
public record ProductCandidate(
        String productId,
        String skuId,
        String productName,
        BigDecimal price,
        String brief,
        String spec,
        String externalRef
) {
}
