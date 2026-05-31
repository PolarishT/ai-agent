package com.bytedance.ai.graph.product.retrieval.scenario;

import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import com.bytedance.ai.shared.metadata.RagChunkType;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 把 {@link ProductQueryIntent} 映射为该 intent 下应当激活的 {@link RagChunkType} 集合。
 *
 * <p>规则：
 * <ul>
 *   <li>PRODUCT_SEARCH / PRODUCT_COMPARE / INVENTORY_CHECK / PRICE_QA
 *       → {@link RagChunkType#PRODUCT_PROFILE}（只查 catalog_product + catalog_sku）；</li>
 *   <li>PRODUCT_RECOMMEND → PRODUCT_PROFILE + MARKETING（推荐场景：profile 决定可售商品，
 *       marketing 补充推荐理由 / 卖点 / 口感 / 适用场景）；</li>
 *   <li>PRODUCT_QA → PRODUCT_PROFILE + MARKETING（详情问答：补充卖点 / 适用场景）；</li>
 *   <li>REVIEW_QA → REVIEW；</li>
 *   <li>FAQ_QA → FAQ_QUERY + FAQ_ANSWER 两条通道（问题语义命中 / 答案文本命中分别召回）。</li>
 * </ul>
 *
 * <p>返回 {@link LinkedHashSet} 让 router 按 intent-specific 顺序调度，便于上层按主路径优先解释命中。
 * 当 MARKETING 与 PRODUCT_PROFILE 同时存在时，PRODUCT_PROFILE 永远排在前面 —— marketing 只能
 * 作为已被 profile 接纳的可售商品的补充加分，不能单独决定库存 / 价格 / 上下架。
 */
@Component
public class IntentEvidenceTypeResolver {

    public Set<RagChunkType> resolve(ProductQueryIntent intent) {
        if (intent == null) {
            return EnumSet.of(RagChunkType.PRODUCT_PROFILE);
        }
        return switch (intent) {
            case PRODUCT_SEARCH, PRODUCT_COMPARE, INVENTORY_CHECK, PRICE_QA ->
                    EnumSet.of(RagChunkType.PRODUCT_PROFILE);
            case PRODUCT_RECOMMEND, PRODUCT_QA ->
                    orderedSet(RagChunkType.PRODUCT_PROFILE, RagChunkType.MARKETING);
            case REVIEW_QA ->
                    EnumSet.of(RagChunkType.REVIEW);
            case FAQ_QA ->
                    orderedSet(RagChunkType.FAQ_QUERY, RagChunkType.FAQ_ANSWER);
        };
    }

    private static Set<RagChunkType> orderedSet(RagChunkType... types) {
        Set<RagChunkType> set = new LinkedHashSet<>();
        for (RagChunkType type : types) {
            set.add(type);
        }
        return set;
    }
}
