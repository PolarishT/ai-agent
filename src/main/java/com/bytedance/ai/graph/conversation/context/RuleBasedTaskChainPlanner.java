package com.bytedance.ai.graph.conversation.context;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 规则版任务链规划器。基于"购物核心动作流"硬编码合法过渡：
 *
 * <pre>
 *  PRODUCT  ──┬──►  PRODUCT
 *             └──►  CART  ──┬──►  CART
 *                           └──►  ORDER  ──►  ORDER
 *
 *  POLICY_QA / REVIEW_SUMMARY: 同组可续，跨组不续
 *  CLARIFY / SMALL_TALK / UNKNOWN: 不参与续接（永远新链）
 * </pre>
 *
 * <p>逻辑：找最近的 EXECUTING chain，若其末步 intent 与当前 intent 在合法过渡里就 append；否则 cancel 旧链开新链。
 *
 * <p>后续替换为 LLM 驱动 planner 时实现同一个 {@link TaskChainPlanner} 接口即可。
 */
@Service
public class RuleBasedTaskChainPlanner implements TaskChainPlanner {

    private enum IntentGroup {
        PRODUCT, CART, ORDER, AUX, META
    }

    private final TaskChainCoordinator coordinator;

    public RuleBasedTaskChainPlanner(TaskChainCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public PlanResult plan(TurnInput input) {
        // META intent (CLARIFY/SMALL_TALK/UNKNOWN) 不形成任务目标 —— 跳过 chain 记录，不动旧链
        if (groupOf(input.intent()) == IntentGroup.META) {
            return new PlanResult(null, null);
        }
        LocalDateTime now = LocalDateTime.now();
        ConversationRuntimeContext.TaskChain active = findActiveChain(input.context());

        if (active != null && canContinue(active, input.intent())) {
            return continueChain(active, input, now);
        }
        String chainToCancel = (active != null) ? active.taskChainId() : null;
        return newChain(input, now, chainToCancel);
    }

    private ConversationRuntimeContext.TaskChain findActiveChain(ConversationRuntimeContext context) {
        if (context == null) {
            return null;
        }
        return context.taskChains().stream()
                .filter(c -> TaskChainCoordinator.CHAIN_STATUS_EXECUTING.equals(c.status()))
                .max(Comparator.comparing(
                        ConversationRuntimeContext.TaskChain::createdAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private boolean canContinue(ConversationRuntimeContext.TaskChain chain, String newIntent) {
        if (chain == null || chain.steps().isEmpty()) {
            return false;
        }
        ConversationRuntimeContext.TaskStep last = chain.steps().get(chain.steps().size() - 1);
        // 末步失败或被取消时不再 append（避免在脏链上累积）
        if (TaskChainCoordinator.STEP_STATUS_FAILED.equals(last.status())
                || TaskChainCoordinator.STEP_STATUS_CANCELLED.equals(last.status())) {
            return false;
        }
        return isCompatibleTransition(last.taskType(), newIntent);
    }

    private boolean isCompatibleTransition(String fromIntent, String toIntent) {
        IntentGroup from = groupOf(fromIntent);
        IntentGroup to = groupOf(toIntent);
        if (from == IntentGroup.META || to == IntentGroup.META) {
            return false;
        }
        if (from == to) {
            return true;
        }
        if (from == IntentGroup.PRODUCT && to == IntentGroup.CART) {
            return true;
        }
        if (from == IntentGroup.CART && to == IntentGroup.ORDER) {
            return true;
        }
        return false;
    }

    private static IntentGroup groupOf(String intent) {
        if (intent == null) {
            return IntentGroup.META;
        }
        return switch (intent) {
            case "PRODUCT_SEARCH", "PRODUCT_RECOMMEND", "PRODUCT_COMPARE",
                 "PRODUCT_DETAIL_QUERY", "PRODUCT_QUERY",
                 "PRICE_QUERY", "INVENTORY_QUERY" -> IntentGroup.PRODUCT;
            case "ADD_TO_CART", "REMOVE_FROM_CART", "UPDATE_CART_ITEM", "CART_MANAGE" -> IntentGroup.CART;
            case "CREATE_ORDER", "CONFIRM_ORDER", "CANCEL_ORDER",
                 "ORDER_QUERY", "LOGISTICS_QUERY" -> IntentGroup.ORDER;
            case "POLICY_QA", "REVIEW_SUMMARY" -> IntentGroup.AUX;
            default -> IntentGroup.META;
        };
    }

    private PlanResult continueChain(
            ConversationRuntimeContext.TaskChain active,
            TurnInput input,
            LocalDateTime now
    ) {
        int nextStepNo = active.steps().size() + 1;
        ConversationRuntimeContext.TaskStep newStep = new ConversationRuntimeContext.TaskStep(
                nextStepNo,
                coordinator.newStepId(),
                input.intent(),
                input.userMessage(),
                input.workflow(),
                input.success()
                        ? TaskChainCoordinator.STEP_STATUS_SUCCEEDED
                        : TaskChainCoordinator.STEP_STATUS_FAILED,
                input.turnId(),
                now,
                now,
                input.output()
        );
        List<ConversationRuntimeContext.TaskStep> newSteps = new ArrayList<>(active.steps());
        newSteps.add(newStep);
        String chainStatus = input.success()
                ? TaskChainCoordinator.CHAIN_STATUS_EXECUTING
                : TaskChainCoordinator.CHAIN_STATUS_FAILED;
        ConversationRuntimeContext.TaskChain updated = new ConversationRuntimeContext.TaskChain(
                null,
                active.taskChainId(),
                active.schemaVersion(),
                chainStatus,
                active.userGoal(),
                newSteps,
                active.createdTurnId(),
                input.turnId(),
                active.createdAt() == null ? now : active.createdAt(),
                now
        );
        return new PlanResult(updated, null);
    }

    private PlanResult newChain(TurnInput input, LocalDateTime now, String chainToCancel) {
        String stepStatus = input.success()
                ? TaskChainCoordinator.STEP_STATUS_SUCCEEDED
                : TaskChainCoordinator.STEP_STATUS_FAILED;
        String chainStatus = input.success()
                ? TaskChainCoordinator.CHAIN_STATUS_EXECUTING
                : TaskChainCoordinator.CHAIN_STATUS_FAILED;
        ConversationRuntimeContext.TaskStep step = new ConversationRuntimeContext.TaskStep(
                1,
                coordinator.newStepId(),
                input.intent(),
                input.userMessage(),
                input.workflow(),
                stepStatus,
                input.turnId(),
                now,
                now,
                input.output()
        );
        ConversationRuntimeContext.UserGoal goal = new ConversationRuntimeContext.UserGoal(
                input.intent(),
                input.userMessage(),
                false,
                chainStatus
        );
        ConversationRuntimeContext.TaskChain chain = new ConversationRuntimeContext.TaskChain(
                null,
                coordinator.newChainId(),
                TaskChainCoordinator.CURRENT_SCHEMA_VERSION,
                chainStatus,
                goal,
                List.of(step),
                input.turnId(),
                input.turnId(),
                now,
                now
        );
        return new PlanResult(chain, chainToCancel);
    }
}
