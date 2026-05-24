/**
 * RAG Agent 模块：面向电商导购的一轮式编排入口。
 *
 * <p>模块边界：对外只暴露 {@code agent.api}；内部按 application / intent / slot /
 * memory / tool / answer / persistence / web 分层。多轮记忆复用 retrieval 暴露的会话 SPI，
 * 商品、购物车与订单能力分别只依赖 catalog/cart/order 的 {@code api} 命名接口。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "RAG Agent",
        allowedDependencies = {
                "common",
                "shared",
                "catalog::api",
                "cart::api",
                "order::api",
                "retrieval::api",
                "retrieval::spi",
        }
)
package com.bytedance.ai.agent;
