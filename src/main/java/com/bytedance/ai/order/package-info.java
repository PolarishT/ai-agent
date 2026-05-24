/**
 * Order 模块：从会话购物车生成订单，负责价格确认、库存扣减和订单详情查询。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "RAG Order",
        allowedDependencies = {"common", "shared", "cart::api", "catalog::api"}
)
package com.bytedance.ai.order;
