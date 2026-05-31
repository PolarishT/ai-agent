package com.bytedance.ai.graph.product.query;

import java.util.List;

/**
 * 单个属性维度（颜色 / 尺寸 / 材质 等）的 include / exclude 集合。
 *
 * <p>同一个 token 同时出现在两个集合时，由 {@code ProductQueryConditionValidator}
 * 按 "exclude 优先" 规则从 {@code include} 中剔除。
 */
public record AttributeIncludeExclude(
        List<String> include,
        List<String> exclude
) {

    public AttributeIncludeExclude {
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    public static AttributeIncludeExclude empty() {
        return new AttributeIncludeExclude(List.of(), List.of());
    }

    public boolean isEmpty() {
        return include.isEmpty() && exclude.isEmpty();
    }
}
