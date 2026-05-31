/**
 * Spring AI Alibaba Graph 编排模块：承载新版电商导购单 StateGraph 骨架。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Spring AI Alibaba Graph",
        allowedDependencies = {
                "graph.cart::api",
                "graph.catalog::api"
        }
)
package com.bytedance.ai.graph;
