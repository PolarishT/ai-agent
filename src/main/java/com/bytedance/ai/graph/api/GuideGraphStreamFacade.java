package com.bytedance.ai.graph.api;

import reactor.core.publisher.Flux;

/**
 * 导购 Graph 流式执行门面，向 Web 层暴露一次用户回合的事件流。
 */
public interface GuideGraphStreamFacade {

    Flux<AgentStreamEvent> turnStream(GuideGraphRequest request);
}
