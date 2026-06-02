package com.bytedance.ai.graph.conversation.context;

/**
 * 任务链规划器：每轮跑完根据「历史 chain + 当前 turn 结果」决定怎么写 chain。
 *
 * <p>纯决策函数，不做持久化（recorder 负责落库）。
 *
 * <p>调用方典型流程：
 * <pre>
 *  TurnInput input = new TurnInput(context, turnId, intent, workflow, success, message, output);
 *  PlanResult result = planner.plan(input);
 *  if (result.previousChainIdToCancel() != null) {
 *      contextManager.transitionChainStatus(..., result.previousChainIdToCancel(), "CANCELLED", turnId);
 *  }
 *  contextManager.saveTaskChain(..., result.chainToPersist(), null);
 * </pre>
 */
public interface TaskChainPlanner {

    PlanResult plan(TurnInput input);

    /** 决策输入：runtime context 由调用方加载好传入，便于测试。 */
    record TurnInput(
            ConversationRuntimeContext context,
            String turnId,
            String intent,
            String workflow,
            boolean success,
            String userMessage,
            ConversationRuntimeContext.StepOutput output
    ) {
    }

    /**
     * 决策输出。
     *
     * @param chainToPersist           要写入存储的 chain（新链或追加步骤后的旧链）
     * @param previousChainIdToCancel  需要先 cancel 的旧 chain id；不需要时为 null
     */
    record PlanResult(
            ConversationRuntimeContext.TaskChain chainToPersist,
            String previousChainIdToCancel
    ) {
    }
}
