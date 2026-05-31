package com.bytedance.ai.graph.product.query;

/**
 * 商品查询属性维度集合，强类型对应 {@code ProductHardFilter} 上的相关字段。
 *
 * <p>当前覆盖 color / size / material 三个常见受规约维度 + capacity 容量字符串；
 * 后续若需要扩展（如 weight / rating），在此 record 上追加字段并同步
 * {@code ProductSearchFilterBuilder} 与 LLM prompt 的 schema 描述。
 */
public record ProductAttributesCondition(
        AttributeIncludeExclude color,
        AttributeIncludeExclude size,
        AttributeIncludeExclude material,
        String capacity
) {

    public ProductAttributesCondition {
        color = color == null ? AttributeIncludeExclude.empty() : color;
        size = size == null ? AttributeIncludeExclude.empty() : size;
        material = material == null ? AttributeIncludeExclude.empty() : material;
    }

    public static ProductAttributesCondition empty() {
        return new ProductAttributesCondition(
                AttributeIncludeExclude.empty(),
                AttributeIncludeExclude.empty(),
                AttributeIncludeExclude.empty(),
                null
        );
    }

    public boolean isEmpty() {
        return color.isEmpty()
                && size.isEmpty()
                && material.isEmpty()
                && (capacity == null || capacity.isBlank());
    }
}
