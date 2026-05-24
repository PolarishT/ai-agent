package com.bytedance.ai.agent.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 规则 / LLM 抽取出的商品检索槽位。
 *
 * <p>W2 起 {@code mustNot} 升级为结构化 {@link MustNot}：分别保留排除的标签、品牌、成分。
 * 旧扁平 list 入口仍兼容，构造时映射进 {@link MustNot#tags()} 以最小化 caller 改动。
 */
public record Slot(
        List<String> must,
        MustNot mustNot,
        PriceRange priceRange,
        String categoryHint,
        List<String> brands,
        String scenario
) {
    public Slot {
        must = copyOrEmpty(must);
        mustNot = mustNot == null ? MustNot.empty() : mustNot;
        brands = copyOrEmpty(brands);
    }

    /**
     * 旧 5 字段构造器（mustNot 是 {@code List<String>}）保持向后兼容；
     * 内部映射到 {@link MustNot#tags()}。
     */
    public Slot(
            List<String> must,
            List<String> mustNot,
            PriceRange priceRange,
            String categoryHint,
            List<String> brands,
            String scenario
    ) {
        this(must, MustNot.ofTags(mustNot), priceRange, categoryHint, brands, scenario);
    }

    public static Slot empty() {
        return new Slot(List.of(), MustNot.empty(), null, null, List.of(), null);
    }

    public boolean isEmpty() {
        return must.isEmpty()
                && mustNot.isEmpty()
                && (priceRange == null || priceRange.isEmpty())
                && !hasText(categoryHint)
                && brands.isEmpty()
                && !hasText(scenario);
    }

    /**
     * 给一份保留所有字段、只替换 {@code mustNot} 的副本，供 {@code NegationSlotExtractor} 合并使用。
     *
     * <p>典型用法：{@code slot = slot.withMustNot(slot.mustNot().merge(negationExtracted))}，
     * 把独立抽到的反选并入主 slot，避免在 {@code Slot} 上加 setter 而破坏 record 不可变性。
     */
    public Slot withMustNot(MustNot newMustNot) {
        return new Slot(must, newMustNot, priceRange, categoryHint, brands, scenario);
    }

    private static List<String> copyOrEmpty(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Slot::hasText)
                .map(String::trim)
                .toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record PriceRange(BigDecimal min, BigDecimal max) {
        public boolean isEmpty() {
            return min == null && max == null;
        }
    }

    /**
     * 结构化反选槽位：tags（功能 / 风格 / 颜色 等通用标签），brands（明确排除的品牌），
     * ingredients（成分 / 材质，如"酒精""香精""ARM 芯片"）。
     *
     * <p>所有列表均不可为空，构造时去空白；所有字段都是"且"关系（任意一项命中即剔除）。
     */
    public record MustNot(List<String> tags, List<String> brands, List<String> ingredients) {

        public MustNot {
            tags = copyOrEmpty(tags);
            brands = copyOrEmpty(brands);
            ingredients = copyOrEmpty(ingredients);
        }

        public static MustNot empty() {
            return new MustNot(List.of(), List.of(), List.of());
        }

        public static MustNot ofTags(List<String> tags) {
            return new MustNot(tags, List.of(), List.of());
        }

        public boolean isEmpty() {
            return tags.isEmpty() && brands.isEmpty() && ingredients.isEmpty();
        }

        /**
         * 合并两个 MustNot：取并集，按 tags / brands / ingredients 分桶。
         */
        public MustNot merge(MustNot other) {
            if (other == null || other.isEmpty()) {
                return this;
            }
            return new MustNot(
                    concatDistinct(this.tags, other.tags),
                    concatDistinct(this.brands, other.brands),
                    concatDistinct(this.ingredients, other.ingredients)
            );
        }

        /**
         * 摊平为字符串列表（保持插入顺序），方便老调用方（answer 模板、回归指标）直接列出"已排除"项；
         * 不区分桶来源——客户端展示「已为您排除：酒精、苹果、黑色」时不需要桶名。
         */
        public List<String> flatten() {
            if (isEmpty()) {
                return List.of();
            }
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
            all.addAll(tags);
            all.addAll(brands);
            all.addAll(ingredients);
            return List.copyOf(all);
        }

        private static List<String> concatDistinct(List<String> a, List<String> b) {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(a);
            set.addAll(b);
            return List.copyOf(set);
        }
    }
}
