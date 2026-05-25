package com.bytedance.ai.agent.workflow;

import org.springframework.stereotype.Component;

@Component
public class RagExecutor {

    public String answer(String question, WorkflowRuntimeState state) {
        if (state.cards().isEmpty()) {
            return "我还没有足够的商品信息回答这个问题，可以先告诉我你想看的商品或需求。";
        }
        return "我会基于当前候选商品回答：" + question;
    }
}
