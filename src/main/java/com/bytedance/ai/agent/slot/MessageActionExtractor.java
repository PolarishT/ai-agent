package com.bytedance.ai.agent.slot;

import java.util.List;

/**
 * 动作槽抽取器：把"购物车子动作 / 数量 / 价格确认 / 对比维度"这 4 类
 * 原先散落在 {@code AgentTurnService} 里的中文正则集中起来，统一交给 LLM 抽取，
 * LLM 不可用 / 异常时本地正则兜底。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>一次 LLM 调用拿全 4 个字段，避免 4 次 round-trip 拖延迟。</li>
 *   <li>只在 {@code CART_OP} / {@code COMPARE} 等真正需要的 intent 上调用；
 *       {@code RECOMMEND_VAGUE / FILTER_BY_ATTR / REFINE} 等纯检索意图直接走 {@link MessageAction#defaults()}。</li>
 *   <li>降级路径返回的字段语义与 LLM 一致（CartAction.ADD / quantity=1 /
 *       priceChangeConfirmed=false / 空 compareAspects），调用方无需区分。</li>
 * </ul>
 */
public interface MessageActionExtractor {

    /**
     * 抽取一次完整的 {@link MessageAction}。{@code null} / 空白消息返回 {@link MessageAction#defaults()}。
     */
    MessageAction extract(String message);

    /**
     * 抽取结果合集。所有字段都有合理默认值，方便上游"不需要时直接读默认"。
     */
    record MessageAction(
            CartAction cartAction,
            int quantity,
            boolean priceChangeConfirmed,
            List<String> compareAspects
    ) {
        public MessageAction {
            cartAction = cartAction == null ? CartAction.ADD : cartAction;
            quantity = Math.max(1, quantity);
            compareAspects = compareAspects == null ? List.of() : List.copyOf(compareAspects);
        }

        public static MessageAction defaults() {
            return new MessageAction(CartAction.ADD, 1, false, List.of());
        }
    }

    /**
     * 购物车子动作枚举，与 cart 工具的 {@code TOOL_NAME} 一一对应；
     * {@link #toolName()} 给 {@code AgentTurnService} 用来做 intent → toolCallback 的分流。
     */
    enum CartAction {
        ADD("add_to_cart"),
        LIST("list_cart"),
        REMOVE("remove_from_cart"),
        UPDATE_QTY("update_cart_qty"),
        PLACE_ORDER("place_order");

        private final String toolName;

        CartAction(String toolName) {
            this.toolName = toolName;
        }

        public String toolName() {
            return toolName;
        }

        /** 容错解析：LLM 输出大小写不规范 / 拼错时一律落回 ADD（购物车默认动作）。 */
        public static CartAction fromName(String raw) {
            if (raw == null) {
                return ADD;
            }
            String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
            for (CartAction action : values()) {
                if (action.name().equals(normalized)) {
                    return action;
                }
            }
            return ADD;
        }
    }
}
