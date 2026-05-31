/**
 * 商品检索内部组件：keyword / semantic 召回器、RRF 融合、基础 ranker、
 * 以及对 {@code graph.product.query} 子图暴露的 {@code ProductSearchSpi} 适配。
 *
 * <p>本包属于 {@code com.bytedance.ai.graph} 主 Modulith 模块，
 * 不单独声明 {@code @ApplicationModule}，依赖与主图共享。
 */
package com.bytedance.ai.graph.product.retrieval;
