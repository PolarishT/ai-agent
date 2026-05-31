/**
 * Catalog 模块：电商商品域（Product / SKU / 价格 / 库存 / LLM 抽取的结构化属性）。
 *
 * <p>导入与变更会写入多篇 {@code rag_documents}（{@code PRODUCT_PROFILE / PRODUCT_KNOWLEDGE / PRODUCT_FAQ / PRODUCT_REVIEW}），
 * 借助 document 模块对外发布的 {@code DocumentIndexRequestedEvent} 自动触发既有索引链路；
 * indexing 模块无需感知 catalog 的存在。
 *
 * <p>属性抽取走 {@code catalog_attribute_outbox} 表 + RocketMQ topic {@code catalog-attribute-extract}，
 * 由 dispatcher 投递 / consumer 异步调 LLM 回填 {@code attributes_json}，
 * 为后续反选/对比/场景化推荐提供数据基础。详见 {@code AGENT.md §3.9}。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "RAG Catalog",
        allowedDependencies = {"common", "shared", "document::api"}
)
package com.bytedance.ai.graph.catalog;
