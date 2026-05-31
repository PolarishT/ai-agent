package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogProductCreateRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把商品 import payload 渲染为 RAG document markdown。
 *
 * <p>原则：chunk 是 evidence，只承载相对稳定的可检索文本。库存 / 实时价格 / SKU 状态 / 上下架状态
 * 等动态业务事实必须来自 {@code catalog_product} / {@code catalog_sku} 原始表，不允许写入 chunk_text。
 * 因此本 renderer 在 {@code renderProfile} 中刻意省略价格、库存、SKU 价格、SKU 库存，
 * 只保留品牌、类目、子类目、规格 KV 以及 attributes/rawJson 中明确属于稳定描述类的字段
 * （description / summary / sellingPoints / 使用场景）。
 */
@Component
class ProductMarkdownRenderer {

    private static final List<String> DESCRIPTION_KEYS = List.of(
            "description", "summary", "intro", "introduction",
            "sellingPoints", "selling_points",
            "usageScenario", "usage_scenario",
            "highlights"
    );

    String renderProfile(CatalogProductCreateRequest product) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(product.title()).append("\n\n");
        appendLine(sb, "品牌", product.brand());
        appendLine(sb, "类目", product.category());
        appendLine(sb, "子类目", product.subCategory());
        appendStableAttributes(sb, product.attributesJson());
        appendDescriptionLikeFields(sb, product.attributesJson());
        appendDescriptionLikeFields(sb, product.rawJson());
        appendSkuSpecs(sb, product.skus());
        return sb.toString().trim();
    }

    String renderKnowledge(CatalogProductCreateRequest.KnowledgeDraft draft) {
        String title = StringUtils.hasText(draft.title()) ? draft.title() : draft.knowledgeType();
        return "# " + title + "\n\n" + draft.content().trim();
    }

    String renderFaq(CatalogProductCreateRequest.FaqDraft draft) {
        return "# FAQ\n\n## 问题\n" + draft.question().trim() + "\n\n## 答案\n" + draft.answer().trim();
    }

    String renderReview(CatalogProductCreateRequest.ReviewDraft draft) {
        StringBuilder sb = new StringBuilder("# 用户评价\n\n");
        if (StringUtils.hasText(draft.nickname())) {
            sb.append("**用户**：").append(draft.nickname()).append("\n");
        }
        if (draft.rating() != null) {
            sb.append("**评分**：").append(draft.rating()).append("\n");
        }
        if (StringUtils.hasText(draft.sentiment())) {
            sb.append("**情感**：").append(draft.sentiment()).append("\n");
        }
        sb.append("\n").append(draft.content().trim());
        return sb.toString().trim();
    }

    String renderReviewSummary(String productTitle, String summary) {
        return "# " + productTitle + " 用户评价总结\n\n" + summary.trim();
    }

    private void appendLine(StringBuilder sb, String label, String value) {
        if (StringUtils.hasText(value)) {
            sb.append("**").append(label).append("**：").append(value).append("\n");
        }
    }

    private void appendStableAttributes(StringBuilder sb, Map<String, Object> attributesJson) {
        if (attributesJson == null || attributesJson.isEmpty()) {
            return;
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : attributesJson.entrySet()) {
            String key = entry.getKey();
            if (key == null || isDynamicKey(key) || isDescriptionLikeKey(key)) {
                continue;
            }
            if (entry.getValue() == null) {
                continue;
            }
            filtered.put(key, entry.getValue());
        }
        if (filtered.isEmpty()) {
            return;
        }
        sb.append("\n## 商品属性\n");
        for (Map.Entry<String, Object> entry : filtered.entrySet()) {
            sb.append("- ").append(entry.getKey()).append("：").append(entry.getValue()).append('\n');
        }
    }

    private void appendDescriptionLikeFields(StringBuilder sb, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (String key : DESCRIPTION_KEYS) {
            Object value = source.get(key);
            String text = renderMultiLineValue(value);
            if (StringUtils.hasText(text)) {
                sb.append("\n## ").append(humanizeKey(key)).append('\n').append(text.trim()).append('\n');
            }
        }
    }

    private void appendSkuSpecs(StringBuilder sb, List<CatalogProductCreateRequest.SkuDraft> skus) {
        if (skus == null || skus.isEmpty()) {
            return;
        }
        // 只渲染 SKU 规格 KV（颜色 / 尺寸 / 容量 ...），刻意省略价格与库存，
        // 让最终展示阶段从 catalog_sku 原始表取实时值。
        List<String> specs = new java.util.ArrayList<>();
        for (CatalogProductCreateRequest.SkuDraft sku : skus) {
            Map<String, Object> properties = sku.propertiesJson();
            if (properties == null || properties.isEmpty()) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (!first) {
                    line.append(" / ");
                }
                line.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
            if (line.length() > 0) {
                specs.add(line.toString());
            }
        }
        if (specs.isEmpty()) {
            return;
        }
        sb.append("\n## 规格\n");
        for (String spec : specs) {
            sb.append("- ").append(spec).append('\n');
        }
    }

    private boolean isDynamicKey(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("price")
                || lower.equals("priceMin".toLowerCase(java.util.Locale.ROOT))
                || lower.equals("priceMax".toLowerCase(java.util.Locale.ROOT))
                || lower.contains("stock")
                || lower.contains("inventory")
                || lower.equals("status")
                || lower.contains("promotion")
                || lower.contains("discount");
    }

    private boolean isDescriptionLikeKey(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        for (String descriptionKey : DESCRIPTION_KEYS) {
            if (lower.equals(descriptionKey.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String humanizeKey(String key) {
        return switch (key) {
            case "description", "intro", "introduction" -> "商品描述";
            case "summary" -> "商品摘要";
            case "sellingPoints", "selling_points" -> "卖点";
            case "usageScenario", "usage_scenario" -> "适用场景";
            case "highlights" -> "亮点";
            default -> key;
        };
    }

    private String renderMultiLineValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    sb.append("- ").append(text).append('\n');
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return String.valueOf(value);
    }
}
