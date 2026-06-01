package com.bytedance.ai.graph.product.query.persistence;

/**
 * pending_product_query_actions 行的生命周期。
 *
 * <ul>
 *   <li>{@code ACTIVE}：当前会话最新一轮，可被下一轮 INHERIT/OVERRIDE/APPEND 复用；</li>
 *   <li>{@code SUPERSEDED}：被同一会话更晚的一行替换；</li>
 *   <li>{@code EXPIRED}：到 expire_at 后由清理任务标记。</li>
 * </ul>
 */
public enum PendingProductQueryStatus {
    ACTIVE,
    SUPERSEDED,
    EXPIRED
}
