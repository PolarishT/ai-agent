package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.AttributeIncludeExclude;
import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductComparisonResult;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductReviewSnippet;
import com.bytedance.ai.graph.product.query.ProductSearchCandidate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 商品查询的确定性文本回复构造器。
 *
 * <p>遵循 spec 第 10 条：
 * <ol>
 *   <li>总结用户条件；</li>
 *   <li>列出商品（最多 5 条）；</li>
 *   <li>说明已排除的条件；</li>
 *   <li>0 命中场景明确指出最严格的条件。</li>
 * </ol>
 *
 * <p>本类不调用任何 LLM，输出可预测、便于测试。
 */
@Component
public class ProductQueryResponseBuilder {

    private static final int MAX_DISPLAY = 5;

    public String buildListResponse(
            ProductQueryCondition condition,
            List<ProductSearchCandidate> candidates,
            List<String> degradedNotes
    ) {
        StringBuilder sb = new StringBuilder();
        appendConditionSummary(sb, condition);
        if (candidates == null || candidates.isEmpty()) {
            appendZeroHitDiagnosis(sb, condition);
            appendDegradedTail(sb, degradedNotes);
            return sb.toString();
        }
        sb.append("\n").append("为你找到 ").append(candidates.size()).append(" 件商品：\n");
        int display = Math.min(candidates.size(), MAX_DISPLAY);
        for (int i = 0; i < display; i++) {
            ProductSearchCandidate candidate = candidates.get(i);
            sb.append(i + 1).append(". ").append(safeTitle(candidate.title()));
            if (candidate.brand() != null && !candidate.brand().isBlank()) {
                sb.append(" - ").append(candidate.brand());
            }
            if (candidate.price() != null) {
                sb.append(" - ¥").append(candidate.price().toPlainString());
            }
            if (candidate.stock() != null && candidate.stock() > 0) {
                sb.append(" - 库存 ").append(candidate.stock());
            }
            if (!candidate.matchReasons().isEmpty()) {
                sb.append("（命中：").append(String.join("、", candidate.matchReasons())).append("）");
            }
            sb.append('\n');
            appendReviews(sb, candidate.reviews());
        }
        if (candidates.size() > MAX_DISPLAY) {
            sb.append("…等共 ").append(candidates.size()).append(" 件。\n");
        }
        appendExcludedSummary(sb, condition);
        appendDegradedTail(sb, degradedNotes);
        return sb.toString().trim();
    }

    public String buildComparisonResponse(
            ProductQueryCondition condition,
            ProductComparisonResult comparison,
            List<String> degradedNotes
    ) {
        StringBuilder sb = new StringBuilder();
        appendConditionSummary(sb, condition);
        if (comparison == null || comparison.rows().isEmpty()) {
            sb.append("\n暂无可对比的商品，请先选择 2 件以上的商品。\n");
            appendDegradedTail(sb, degradedNotes);
            return sb.toString();
        }
        sb.append("\n商品对比（共 ").append(comparison.rows().size()).append(" 件）：\n");
        for (ProductComparisonResult.Row row : comparison.rows()) {
            sb.append("第 ").append(row.index()).append(" 件：")
                    .append(safeTitle(row.title()));
            if (row.brand() != null) {
                sb.append(" - ").append(row.brand());
            }
            if (row.price() != null) {
                sb.append(" - ¥").append(row.price().toPlainString());
            }
            if (row.color() != null) {
                sb.append(" - 颜色 ").append(row.color());
            }
            if (row.capacity() != null) {
                sb.append(" - 容量 ").append(row.capacity());
            }
            if (row.stock() != null) {
                sb.append(" - 库存 ").append(row.stock());
            }
            if (!row.matchReasons().isEmpty()) {
                sb.append("（命中：").append(String.join("、", row.matchReasons())).append("）");
            }
            sb.append('\n');
        }
        if (comparison.summary() != null && !comparison.summary().isBlank()) {
            sb.append('\n').append(comparison.summary()).append('\n');
        }
        appendDegradedTail(sb, degradedNotes);
        return sb.toString().trim();
    }

    public String buildClarifyResponse(ProductQueryCondition condition) {
        StringBuilder sb = new StringBuilder("我需要再确认下你的商品查询条件");
        if (condition != null && !condition.missingSlots().isEmpty()) {
            sb.append("（缺少：").append(String.join("、", condition.missingSlots())).append("）");
        }
        sb.append("。请补充：是否限定品类、价格区间、品牌或颜色，以及是否要求有库存。");
        return sb.toString();
    }

    private void appendConditionSummary(StringBuilder sb, ProductQueryCondition condition) {
        if (condition == null) {
            sb.append("你的商品查询：（条件未识别）。");
            return;
        }
        sb.append("你想找：");
        List<String> parts = new ArrayList<>();
        if (!condition.brandTerms().isEmpty()) {
            parts.add(String.join("/", condition.brandTerms()) + " 品牌");
        }
        if (!condition.categoryTerms().isEmpty()) {
            parts.add(String.join("/", condition.categoryTerms()));
        }
        if (!condition.includeTerms().isEmpty()) {
            parts.add("含 " + String.join("、", condition.includeTerms()));
        }
        ProductAttributesCondition attributes = condition.attributes();
        appendAttributeInclude(parts, "颜色", attributes.color());
        appendAttributeInclude(parts, "尺寸", attributes.size());
        appendAttributeInclude(parts, "材质", attributes.material());
        if (attributes.capacity() != null && !attributes.capacity().isBlank()) {
            parts.add("容量 " + attributes.capacity());
        }
        appendPriceSummary(parts, condition.priceMin(), condition.priceMax());
        if (parts.isEmpty()) {
            sb.append(condition.normalizedQuery() == null ? condition.rawQuery() : condition.normalizedQuery());
        } else {
            sb.append(String.join("，", parts));
        }
        sb.append("的商品。");
    }

    private void appendExcludedSummary(StringBuilder sb, ProductQueryCondition condition) {
        if (condition == null) {
            return;
        }
        List<String> excludes = new ArrayList<>();
        if (!condition.excludeTerms().isEmpty()) {
            excludes.add("关键词: " + String.join("、", condition.excludeTerms()));
        }
        appendAttributeExclude(excludes, "颜色", condition.attributes().color());
        appendAttributeExclude(excludes, "尺寸", condition.attributes().size());
        appendAttributeExclude(excludes, "材质", condition.attributes().material());
        if (!excludes.isEmpty()) {
            sb.append("已为你排除：").append(String.join("；", excludes)).append("。\n");
        }
    }

    private void appendZeroHitDiagnosis(StringBuilder sb, ProductQueryCondition condition) {
        sb.append("\n暂时没有找到完全符合条件的商品。");
        if (condition == null) {
            sb.append("建议换个关键词重新搜索。");
            return;
        }
        String tightest = strictestConstraint(condition);
        if (tightest != null) {
            sb.append("当前最严格的条件是【").append(tightest).append("】，可以尝试放宽该条件再试一次。");
        } else {
            sb.append("建议换一组关键词或放宽筛选条件。");
        }
    }

    private String strictestConstraint(ProductQueryCondition condition) {
        if (condition.priceMax() != null) {
            return "价格 ≤ " + condition.priceMax().toPlainString();
        }
        if (!condition.attributes().color().exclude().isEmpty()) {
            return "排除颜色 " + String.join("/", condition.attributes().color().exclude());
        }
        if (!condition.excludeTerms().isEmpty()) {
            return "排除关键词 " + String.join("/", condition.excludeTerms());
        }
        if (!condition.brandTerms().isEmpty()) {
            return "限定品牌 " + String.join("/", condition.brandTerms());
        }
        if (!condition.categoryTerms().isEmpty()) {
            return "限定品类 " + String.join("/", condition.categoryTerms());
        }
        return null;
    }

    private void appendDegradedTail(StringBuilder sb, List<String> degradedNotes) {
        if (degradedNotes == null || degradedNotes.isEmpty()) {
            return;
        }
        sb.append("\n提示：本轮检索发生部分降级 (")
                .append(String.join(", ", degradedNotes))
                .append(")，结果可能不完整。\n");
    }

    private void appendReviews(StringBuilder sb, List<ProductReviewSnippet> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return;
        }
        int display = Math.min(reviews.size(), 3);
        for (int i = 0; i < display; i++) {
            ProductReviewSnippet review = reviews.get(i);
            sb.append("   评论").append(i + 1).append("：");
            if (review.rating() != null) {
                sb.append(review.rating()).append("星，");
            }
            if (review.sentiment() != null && !review.sentiment().isBlank()) {
                sb.append(review.sentiment()).append("，");
            }
            if (review.nickname() != null && !review.nickname().isBlank()) {
                sb.append(review.nickname()).append("说：");
            }
            sb.append(safeReviewContent(review.content())).append('\n');
        }
    }

    private String safeReviewContent(String content) {
        if (content == null || content.isBlank()) {
            return "未填写评价内容";
        }
        String normalized = content.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    private void appendAttributeInclude(List<String> parts, String label, AttributeIncludeExclude attr) {
        if (attr == null || attr.include().isEmpty()) {
            return;
        }
        parts.add(label + " " + String.join("/", attr.include()));
    }

    private void appendAttributeExclude(List<String> excludes, String label, AttributeIncludeExclude attr) {
        if (attr == null || attr.exclude().isEmpty()) {
            return;
        }
        excludes.add(label + " 排除 " + String.join("/", attr.exclude()));
    }

    private void appendPriceSummary(List<String> parts, BigDecimal priceMin, BigDecimal priceMax) {
        if (priceMin == null && priceMax == null) {
            return;
        }
        if (priceMin != null && priceMax != null) {
            parts.add("价格 ¥" + priceMin.toPlainString() + " - ¥" + priceMax.toPlainString());
        } else if (priceMax != null) {
            parts.add("价格 ≤ ¥" + priceMax.toPlainString());
        } else {
            parts.add("价格 ≥ ¥" + priceMin.toPlainString());
        }
    }

    private String safeTitle(String title) {
        return title == null || title.isBlank() ? "未命名商品" : title;
    }
}
