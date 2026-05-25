package com.bytedance.ai.shared.metadata;

import com.bytedance.ai.shared.support.RagJsonCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 负责解析 chunk metadata，并提供统一的过滤判断。
 */
@Component
public class RagChunkMetadataHelper {

    private static final Logger log = LoggerFactory.getLogger(RagChunkMetadataHelper.class);

    private final RagJsonCodec jsonCodec;

    public RagChunkMetadataHelper(RagJsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    public RagChunkMetadataView parse(String metadataJson) {
        Map<String, Object> raw = parseRaw(metadataJson);
        return new RagChunkMetadataView(
                asText(raw.get("blockType")),
                RagChunkType.parseOrBody(asText(raw.get("chunkType"))),
                asText(raw.get("codeLanguage")),
                toStringList(raw.get("headingPath")),
                toStringList(raw.get("documentTags")),
                raw
        );
    }

    public boolean matches(String sourceUri, RagChunkMetadataView metadataView, RagSearchFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }

        if (StringUtils.hasText(filter.sourceUriPrefix())
                && !startsWithIgnoreCase(sourceUri, filter.sourceUriPrefix())) {
            return false;
        }

        if (filter.tags() != null && !filter.tags().isEmpty()) {
            List<String> lowerCaseTags = metadataView.documentTags().stream()
                    .map(this::normalizeLowerCase)
                    .toList();
            boolean allMatch = filter.tags().stream()
                    .map(this::normalizeLowerCase)
                    .allMatch(lowerCaseTags::contains);
            if (!allMatch) {
                return false;
            }
        }

        if (StringUtils.hasText(filter.headingPathContains())) {
            String keyword = normalizeLowerCase(filter.headingPathContains());
            boolean matched = metadataView.headingPath().stream()
                    .map(this::normalizeLowerCase)
                    .anyMatch(segment -> segment.contains(keyword));
            if (!matched) {
                return false;
            }
        }

        if (!filter.mustNotTags().isEmpty()) {
            List<String> lowerCaseTags = metadataView.documentTags().stream()
                    .map(this::normalizeLowerCase)
                    .toList();
            boolean hitForbidden = filter.mustNotTags().stream()
                    .map(this::normalizeLowerCase)
                    .anyMatch(lowerCaseTags::contains);
            if (hitForbidden) {
                return false;
            }
        }

        if (!filter.mustNotBrands().isEmpty()) {
            Object brand = metadataView.raw().get("brand");
            if (brand != null) {
                String brandText = normalizeLowerCase(String.valueOf(brand));
                boolean blocked = filter.mustNotBrands().stream()
                        .map(this::normalizeLowerCase)
                        .anyMatch(brandText::equals);
                if (blocked) {
                    return false;
                }
            }
        }

        // mustNotIngredients 在召回阶段不查 chunk content（这里 metadata 不一定带正文），
        // 交给 NegationRerankFilter 在拿到 hit.snippet / SPU description 后做精过滤。

        return true;
    }

    public List<String> toStringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                .map(String::valueOf)
                .toList();
    }

    public Map<String, Object> parseRaw(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = jsonCodec.readMap(metadataJson);
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            return new LinkedHashMap<>(raw);
        } catch (Exception exception) {
            Map<String, Object> raw = parseJavaMapString(metadataJson);
            if (!raw.isEmpty()) {
                return raw;
            }
            log.warn(
                    "RAG chunk metadata 解析失败，将按空 metadata 处理。error={}, payload={}",
                    exception.getMessage(),
                    abbreviate(metadataJson)
            );
            return Map.of();
        }
    }

    private Map<String, Object> parseJavaMapString(String metadata) {
        String text = metadata == null ? "" : metadata.trim();
        if (!text.startsWith("{") || !text.endsWith("}") || !text.contains("=")) {
            return Map.of();
        }

        Map<String, Object> parsed = new LinkedHashMap<>();
        for (String entry : splitTopLevel(text.substring(1, text.length() - 1))) {
            int separator = entry.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = entry.substring(0, separator).trim();
            String value = entry.substring(separator + 1).trim();
            if (StringUtils.hasText(key)) {
                parsed.put(key, parseJavaMapValue(value));
            }
        }
        return parsed;
    }

    private Object parseJavaMapValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return splitTopLevel(trimmed.substring(1, trimmed.length() - 1)).stream()
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .toList();
        }
        if ("null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private List<String> splitTopLevel(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> parts = new java.util.ArrayList<>();
        int bracketDepth = 0;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '[') {
                bracketDepth++;
            } else if (current == ']' && bracketDepth > 0) {
                bracketDepth--;
            } else if (current == ',' && bracketDepth == 0) {
                parts.add(text.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private boolean startsWithIgnoreCase(String value, String prefix) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(prefix)) {
            return false;
        }
        return normalizeLowerCase(value).startsWith(normalizeLowerCase(prefix));
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String normalizeLowerCase(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 200) {
            return value;
        }
        return value.substring(0, 200) + "...";
    }
}
