package com.bytedance.ai.graph.cartmanage.subgraph.support;

import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CartCandidateMatcher {

    private static final List<String> COLOR_WORDS = List.of(
            "黑色", "黑", "灰色", "灰", "蓝色", "蓝", "白色", "白", "红色", "红",
            "绿色", "绿", "黄色", "黄", "粉色", "粉", "紫色", "紫", "藏青",
            "米色", "棕色", "咖色", "银色", "金色"
    );
    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:寸|英寸|inch|in)\\b?", Pattern.CASE_INSENSITIVE);

    public CartCandidateFilterResult filter(List<ProductCandidate> candidates, CartCandidateConstraints constraints) {
        if (candidates == null || candidates.isEmpty()) {
            return new CartCandidateFilterResult(List.of(), List.of(), 0, 0);
        }
        if (constraints == null || !constraints.hasAny()) {
            return new CartCandidateFilterResult(List.copyOf(candidates), List.of(), candidates.size(), candidates.size());
        }
        List<ProductCandidate> matched = new ArrayList<>();
        Set<String> mismatchReasons = new LinkedHashSet<>();
        int priceMatchedCount = 0;
        int specMatchedCount = 0;
        for (ProductCandidate candidate : candidates) {
            boolean priceMatched = priceMatches(candidate, constraints.expectedPrice());
            boolean productIdentityMatched = productIdentityMatches(candidate, constraints);
            boolean colorMatched = colorMatches(candidate, constraints.colorTokens());
            boolean sizeMatched = sizeMatches(candidate, constraints.sizeTokens());
            boolean specMatched = specMatches(candidate, constraints.specTokens());

            if (priceMatched) {
                priceMatchedCount++;
            } else if (constraints.expectedPrice() != null) {
                mismatchReasons.add("price_not_matched");
            }
            if (colorMatched && sizeMatched && specMatched) {
                specMatchedCount++;
            } else {
                if (!productIdentityMatched) {
                    mismatchReasons.add("product_identity_not_matched");
                }
                if (!colorMatched) {
                    mismatchReasons.add("color_not_matched");
                }
                if (!sizeMatched) {
                    mismatchReasons.add("size_not_matched");
                }
                if (!specMatched) {
                    mismatchReasons.add("spec_not_matched");
                }
            }
            if (priceMatched && productIdentityMatched && colorMatched && sizeMatched && specMatched) {
                matched.add(candidate);
            }
        }
        return new CartCandidateFilterResult(List.copyOf(matched), List.copyOf(mismatchReasons),
                priceMatchedCount, specMatchedCount);
    }

    public String constraintMismatchMessage(
            String productName,
            CartCandidateConstraints constraints,
            List<ProductCandidate> candidates
    ) {
        String displayName = StringUtils.hasText(productName) ? "「" + productName + "」" : "该商品";
        String prefix = "找到了类似商品，但没有满足你指定条件的商品。";
        if (constraints != null && constraints.expectedPrice() != null) {
            return prefix + "你要求价格为 " + formatPrice(constraints.expectedPrice()) + " 的" + displayName
                    + "，但当前可选候选价格为 " + formatPriceList(candidates)
                    + "，价格不匹配。请确认是否换一个价格或关键词。";
        }
        if (constraints != null && constraints.hasColor()) {
            return prefix + "你要求颜色为 " + String.join("/", constraints.colorTokens())
                    + "，但当前候选颜色不匹配。请确认是否换一个颜色或关键词。";
        }
        if (constraints != null && constraints.hasSize()) {
            return prefix + "你要求规格为 " + String.join("/", constraints.sizeTokens())
                    + "，但当前候选规格不匹配。请确认是否换一个规格或关键词。";
        }
        return prefix + "请确认商品名称、规格或价格后重新发送。";
    }

    public List<String> candidatePrices(List<ProductCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .map(ProductCandidate::price)
                .filter(Objects::nonNull)
                .map(this::formatPrice)
                .distinct()
                .toList();
    }

    public String formatPrice(BigDecimal price) {
        if (price == null) {
            return "未知";
        }
        return "¥" + price.stripTrailingZeros().toPlainString();
    }

    private String formatPriceList(List<ProductCandidate> candidates) {
        List<String> prices = candidatePrices(candidates);
        if (prices.isEmpty()) {
            return "未知";
        }
        if (prices.size() == 1) {
            return prices.getFirst();
        }
        return String.join(" 和 ", prices);
    }

    private boolean priceMatches(ProductCandidate candidate, BigDecimal expectedPrice) {
        if (expectedPrice == null) {
            return true;
        }
        return candidate != null && candidate.price() != null
                && candidate.price().compareTo(expectedPrice) == 0;
    }

    private boolean colorMatches(ProductCandidate candidate, List<String> colorTokens) {
        if (colorTokens == null || colorTokens.isEmpty()) {
            return true;
        }
        String text = CartGraphStateSupport.normalizeMatchText(String.join(" ",
                CartGraphStateSupport.nullToEmpty(candidate == null ? null : candidate.spec()),
                CartGraphStateSupport.nullToEmpty(candidate == null ? null : candidate.brief())
        ));
        return colorTokens.stream().anyMatch(text::contains);
    }

    private boolean sizeMatches(ProductCandidate candidate, List<String> sizeTokens) {
        if (sizeTokens == null || sizeTokens.isEmpty()) {
            return true;
        }
        String text = CartGraphStateSupport.normalizeMatchText(String.join(" ",
                CartGraphStateSupport.nullToEmpty(candidate == null ? null : candidate.productName()),
                CartGraphStateSupport.nullToEmpty(candidate == null ? null : candidate.spec()),
                CartGraphStateSupport.nullToEmpty(candidate == null ? null : candidate.brief())
        ));
        return sizeTokens.stream().allMatch(text::contains);
    }

    private boolean specMatches(ProductCandidate candidate, List<String> specTokens) {
        if (specTokens == null || specTokens.isEmpty()) {
            return true;
        }
        String text = CartGraphStateSupport.normalizeMatchText(String.join(" ",
                CartGraphStateSupport.nullToEmpty(candidate == null ? null : candidate.spec()),
                CartGraphStateSupport.nullToEmpty(candidate == null ? null : candidate.brief())
        ));
        return specTokens.stream().allMatch(text::contains);
    }

    private boolean productIdentityMatches(ProductCandidate candidate, CartCandidateConstraints constraints) {
        if (constraints == null) {
            return true;
        }
        if (StringUtils.hasText(constraints.productId())) {
            return candidate != null && constraints.productId().equals(candidate.productId());
        }
        if (StringUtils.hasText(constraints.skuId())) {
            return candidate != null && constraints.skuId().equals(candidate.skuId());
        }
        if (StringUtils.hasText(constraints.productName())) {
            String candidateName = CartGraphStateSupport.normalizeMatchText(candidate == null ? null : candidate.productName());
            String expectedName = CartGraphStateSupport.normalizeMatchText(constraints.productName());
            return !StringUtils.hasText(expectedName)
                    || candidateName.contains(expectedName)
                    || expectedName.contains(candidateName);
        }
        return true;
    }

    public record CartCandidateConstraints(
            String productName,
            String productId,
            String skuId,
            Integer quantity,
            BigDecimal expectedPrice,
            List<String> colorTokens,
            List<String> sizeTokens,
            List<String> specTokens
    ) {
        public CartCandidateConstraints {
            colorTokens = colorTokens == null ? List.of() : List.copyOf(colorTokens);
            sizeTokens = sizeTokens == null ? List.of() : List.copyOf(sizeTokens);
            specTokens = specTokens == null ? List.of() : List.copyOf(specTokens);
        }

        public static CartCandidateConstraints from(
                String userMessage,
                String productName,
                String productId,
                String skuId,
                Integer quantity,
                BigDecimal expectedPrice
        ) {
            String text = String.join(" ",
                    CartGraphStateSupport.nullToEmpty(userMessage),
                    CartGraphStateSupport.nullToEmpty(productName));
            return new CartCandidateConstraints(
                    blankToNull(productName),
                    blankToNull(productId),
                    blankToNull(skuId),
                    quantity,
                    expectedPrice,
                    extractColorTokens(text),
                    extractSizeTokens(text),
                    List.of()
            );
        }

        public boolean hasAny() {
            return StringUtils.hasText(productName)
                    || StringUtils.hasText(productId)
                    || StringUtils.hasText(skuId)
                    || quantity != null
                    || expectedPrice != null
                    || hasColor()
                    || hasSize()
                    || !specTokens.isEmpty();
        }

        public boolean hasColor() {
            return !colorTokens.isEmpty();
        }

        public boolean hasSize() {
            return !sizeTokens.isEmpty();
        }

        private static List<String> extractColorTokens(String text) {
            String normalized = normalizeConstraintText(text);
            if (!StringUtils.hasText(normalized)) {
                return List.of();
            }
            LinkedHashSet<String> colors = new LinkedHashSet<>();
            for (String color : COLOR_WORDS) {
                if (normalized.contains(color)) {
                    colors.add(color);
                    if (color.endsWith("色") && color.length() > 1) {
                        colors.add(color.substring(0, color.length() - 1));
                    }
                }
            }
            return List.copyOf(colors);
        }

        private static List<String> extractSizeTokens(String text) {
            if (!StringUtils.hasText(text)) {
                return List.of();
            }
            Matcher matcher = SIZE_PATTERN.matcher(text);
            LinkedHashSet<String> sizes = new LinkedHashSet<>();
            while (matcher.find()) {
                sizes.add(matcher.group(1) + "寸");
            }
            return List.copyOf(sizes);
        }

        private static String normalizeConstraintText(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        }

        private static String blankToNull(String value) {
            return StringUtils.hasText(value) ? value : null;
        }
    }

    public record CartCandidateFilterResult(
            List<ProductCandidate> matchedCandidates,
            List<String> mismatchReasons,
            int priceMatchedCount,
            int specMatchedCount
    ) {
    }
}
