/**
 * Cart-manage 子图：把"加购 / 删购 / 改数量 / 查购物车"独立成 LLM-driven 子流程。
 *
 * <p>负责会话中的购物车意图识别、slot 抽取、商品候选选择、库存校验与回调 {@code cart} 模块的命令服务。
 *
 * <p>本包属于 {@code com.bytedance.ai.graph} 主 Modulith 模块，不单独声明 {@code @ApplicationModule}。
 */
package com.bytedance.ai.graph.cartmanage;
