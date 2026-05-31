package com.bytedance.ai.graph.product.retrieval;

/**
 * 商品检索 SPI，供 agent 等上层模块调用。
 *
 * <p>实现由 graph product 内部 {@code ProductSearchSpiAdapter} 提供，包装商品专用 RAG retriever
 * 与 catalog 实时数据。调用方不感知内部召回预算、fusion、ranker 等细节。
 */
public interface ProductSearchSpi {

    /**
     * 商品查询专用入口：一次调用拿到分层结果（keyword / semantic / fused / ranked），
     * 让上层 subgraph 可以分别消费而无需多次回调 retrieval。
     *
     * <p>graph product 内部会并发跑 keyword / semantic 两路、做 RRF 融合与基础打分；
     * 单分支失败/超时通过 {@link ProductSearchResult#degradedNotes()} 暴露，调用方决定是否提示用户。
     *
     * @throws IllegalArgumentException 当 query 为空
     */
    ProductSearchResult searchProduct(ProductSearchRequest request);
}
