package com.bytedance.ai.indexing.service;

import com.bytedance.ai.indexing.model.RagTextChunk;
import com.bytedance.ai.shared.markdown.MarkdownDocumentParser;
import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagOpenAiTokenCounter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 对 Markdown 文档执行标题感知分块，并将代码块与正文分离。
 */
@Component
public class RagTextChunker {

    private final RagProperties properties;
    private final MarkdownDocumentParser markdownDocumentParser;
    private final RagOpenAiTokenCounter tokenCounter;

    public RagTextChunker(
            RagProperties properties,
            MarkdownDocumentParser markdownDocumentParser,
            RagOpenAiTokenCounter tokenCounter
    ) {
        this.properties = properties;
        this.markdownDocumentParser = markdownDocumentParser;
        this.tokenCounter = tokenCounter;
    }

    /**
     * 将原始 Markdown 全文切分为多个标题感知片段。
     *
     * @param content 原始 Markdown 正文
     * @return 保留标题路径、块类型、代码语言和元数据的文本切片列表
     */
    public List<RagTextChunk> chunk(String content) {
        MarkdownDocumentParser.MarkdownDocument document = markdownDocumentParser.parse(content);
        if (!StringUtils.hasText(document.bodyContent())) {
            return List.of();
        }

        List<RagTextChunk> chunks = new ArrayList<>();
        int index = 0;
        List<MarkdownDocumentParser.MarkdownBlock> blocks = document.blocks().isEmpty()
                ? List.of(new MarkdownDocumentParser.MarkdownBlock(List.of(), "text", null, document.bodyContent(), Map.of()))
                : document.blocks();

        for (MarkdownDocumentParser.MarkdownBlock block : blocks) {
            for (ChunkPiece piece : splitBlock(block)) {
                String normalized = piece.text().trim();
                if (!normalized.isEmpty()) {
                    chunks.add(new RagTextChunk(
                            index++,
                            normalized,
                            sha256Hex(normalized),
                            normalized.length(),
                            approximateTokenCount(normalized),
                            block.headingPath(),
                            piece.blockType(),
                            piece.codeLanguage(),
                            piece.metadata()
                    ));
                }
            }
        }
        return chunks;
    }

    private List<ChunkPiece> splitBlock(MarkdownDocumentParser.MarkdownBlock block) {
        return switch (block.blockType()) {
            case "code" -> splitCodeBlock(block);
            case "table" -> splitTableBlock(block);
            case "blockquote" -> splitBlockquoteBlock(block);
            default -> splitTextBlock(block);
        };
    }

    private List<ChunkPiece> splitTextBlock(MarkdownDocumentParser.MarkdownBlock block) {
        return splitNarrativeBlock(
                headingPrefix(block.headingPath()),
                block.content().trim(),
                "text",
                null,
                block.blockMetadata()
        );
    }

    private List<ChunkPiece> splitBlockquoteBlock(MarkdownDocumentParser.MarkdownBlock block) {
        return splitNarrativeBlock(
                headingPrefix(block.headingPath()),
                block.content().trim(),
                "blockquote",
                null,
                block.blockMetadata()
        );
    }

    private List<ChunkPiece> splitNarrativeBlock(
            String prefix,
            String body,
            String blockType,
            String codeLanguage,
            Map<String, Object> metadata
    ) {
        int contentBudget = contentBudget(prefix, 0);
        if (body.length() <= contentBudget) {
            return List.of(new ChunkPiece(renderTextChunk(prefix, body), blockType, codeLanguage, metadata));
        }
        List<ChunkPiece> pieces = new ArrayList<>();
        List<String> paragraphs = splitParagraphs(body);
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (paragraph.length() > contentBudget) {
                flushIfPresent(pieces, current, prefix, blockType, codeLanguage, metadata);
                splitLongText(paragraph, contentBudget).stream()
                        .map(piece -> new ChunkPiece(renderTextChunk(prefix, piece), blockType, codeLanguage, metadata))
                        .forEach(pieces::add);
                continue;
            }

            if (current.isEmpty()) {
                current.append(paragraph);
                continue;
            }

            if (current.length() + 2 + paragraph.length() <= contentBudget) {
                current.append("\n\n").append(paragraph);
                continue;
            }

            pieces.add(new ChunkPiece(renderTextChunk(prefix, current.toString()), blockType, codeLanguage, metadata));
            current.setLength(0);
            current.append(paragraph);
        }
        flushIfPresent(pieces, current, prefix, blockType, codeLanguage, metadata);
        return pieces;
    }

    private List<ChunkPiece> splitTableBlock(MarkdownDocumentParser.MarkdownBlock block) {
        String prefix = headingPrefix(block.headingPath());
        String body = block.content().trim();
        List<ChunkPiece> pieces = new ArrayList<>(splitRawTableBlock(prefix, body, rawTableMetadata(block.blockMetadata())));
        pieces.addAll(splitTableRows(prefix, block.blockMetadata()));
        return pieces;
    }

    private List<ChunkPiece> splitRawTableBlock(String prefix, String body, Map<String, Object> metadata) {
        int contentBudget = contentBudget(prefix, 0);
        if (body.length() <= contentBudget) {
            return List.of(new ChunkPiece(renderTableChunk(prefix, body), "table", null, metadata));
        }

        String[] lines = body.split("\n", -1);
        if (lines.length <= 2) {
            return splitNarrativeBlock(prefix, body, "table", null, metadata);
        }

        String tableHead = lines[0] + "\n" + lines[1];
        int rowBudget = Math.max(80, contentBudget - tableHead.length() - 1);
        List<ChunkPiece> pieces = new ArrayList<>();
        StringBuilder currentRows = new StringBuilder();

        for (int index = 2; index < lines.length; index++) {
            String row = lines[index].trim();
            if (row.isEmpty()) {
                continue;
            }

            if (row.length() > rowBudget) {
                if (!currentRows.isEmpty()) {
                    pieces.add(new ChunkPiece(renderTableChunk(prefix, tableHead + "\n" + currentRows), "table", null, metadata));
                    currentRows.setLength(0);
                }
                splitLongText(row, rowBudget).stream()
                        .map(piece -> new ChunkPiece(renderTableChunk(prefix, tableHead + "\n" + piece), "table", null, metadata))
                        .forEach(pieces::add);
                continue;
            }

            if (currentRows.isEmpty()) {
                currentRows.append(row);
                continue;
            }

            if (currentRows.length() + 1 + row.length() <= rowBudget) {
                currentRows.append('\n').append(row);
                continue;
            }

            pieces.add(new ChunkPiece(renderTableChunk(prefix, tableHead + "\n" + currentRows), "table", null, metadata));
            currentRows.setLength(0);
            currentRows.append(row);
        }

        if (!currentRows.isEmpty()) {
            pieces.add(new ChunkPiece(renderTableChunk(prefix, tableHead + "\n" + currentRows), "table", null, metadata));
        }
        return pieces;
    }

    private List<ChunkPiece> splitTableRows(String prefix, Map<String, Object> blockMetadata) {
        Object rawRows = blockMetadata.get("tableRows");
        if (!(rawRows instanceof List<?> rows) || rows.isEmpty()) {
            return List.of();
        }

        List<ChunkPiece> pieces = new ArrayList<>();
        for (Object rawRow : rows) {
            if (!(rawRow instanceof Map<?, ?> rowMap)) {
                continue;
            }

            String rowText = renderTableRowDescription(rowMap);
            if (!StringUtils.hasText(rowText)) {
                continue;
            }

            Map<String, Object> metadata = tableRowMetadata(blockMetadata, rowMap);
            pieces.addAll(splitNarrativeBlock(prefix, rowText, "table_row", null, metadata));
        }
        return pieces;
    }

    private List<ChunkPiece> splitCodeBlock(MarkdownDocumentParser.MarkdownBlock block) {
        String prefix = headingPrefix(block.headingPath());
        String language = block.codeLanguage() == null ? "" : block.codeLanguage();
        String fenceHeader = "```" + language + "\n";
        String fenceFooter = "\n```";
        int contentBudget = contentBudget(prefix, fenceHeader.length() + fenceFooter.length());
        String code = block.content().trim();
        if (code.length() <= contentBudget) {
            return List.of(new ChunkPiece(renderCodeChunk(prefix, code, language), "code", block.codeLanguage(), block.blockMetadata()));
        }

        List<ChunkPiece> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            if (line.length() > contentBudget) {
                if (!current.isEmpty()) {
                    pieces.add(new ChunkPiece(renderCodeChunk(prefix, current.toString(), language), "code", block.codeLanguage(), block.blockMetadata()));
                    current.setLength(0);
                }
                splitLongText(line, contentBudget).stream()
                        .map(piece -> new ChunkPiece(renderCodeChunk(prefix, piece, language), "code", block.codeLanguage(), block.blockMetadata()))
                        .forEach(pieces::add);
                continue;
            }

            if (current.isEmpty()) {
                current.append(line);
                continue;
            }

            if (current.length() + 1 + line.length() <= contentBudget) {
                current.append('\n').append(line);
                continue;
            }

            pieces.add(new ChunkPiece(renderCodeChunk(prefix, current.toString(), language), "code", block.codeLanguage(), block.blockMetadata()));
            current.setLength(0);
            current.append(line);
        }
        if (!current.isEmpty()) {
            pieces.add(new ChunkPiece(renderCodeChunk(prefix, current.toString(), language), "code", block.codeLanguage(), block.blockMetadata()));
        }
        return pieces;
    }

    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String trimmed = paragraph.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs.isEmpty() ? List.of(text.trim()) : paragraphs;
    }

    private List<String> splitLongText(String text, int contentBudget) {
        int overlap = Math.min(properties.chunkOverlap(), Math.max(0, contentBudget - 1));
        int step = Math.max(1, contentBudget - overlap);
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            int end = bestSplitEnd(text, start, Math.min(text.length(), start + contentBudget));
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= text.length()) {
                break;
            }
        }
        return chunks;
    }

    private int bestSplitEnd(String text, int start, int end) {
        int searchStart = Math.max(start + 1, end - 40);
        for (int index = end; index > searchStart; index--) {
            char current = text.charAt(index - 1);
            if (Character.isWhitespace(current) || current == '。' || current == '，' || current == ',' || current == '.') {
                return index;
            }
        }
        return end;
    }

    private String headingPrefix(List<String> headingPath) {
        if (headingPath == null || headingPath.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < headingPath.size(); index++) {
            builder.append("#".repeat(index + 1))
                    .append(' ')
                    .append(headingPath.get(index))
                    .append('\n');
        }
        builder.append('\n');
        return builder.toString();
    }

    private int contentBudget(String prefix, int suffixLength) {
        return Math.max(80, properties.chunkSize() - prefix.length() - suffixLength);
    }

    private String renderTextChunk(String prefix, String body) {
        return prefix.isEmpty() ? body : prefix + body;
    }

    private String renderCodeChunk(String prefix, String code, String language) {
        String fenced = "```" + (language == null ? "" : language) + "\n" + code + "\n```";
        return prefix.isEmpty() ? fenced : prefix + fenced;
    }

    private String renderTableChunk(String prefix, String table) {
        return prefix.isEmpty() ? table : prefix + table;
    }

    private Map<String, Object> rawTableMetadata(Map<String, Object> blockMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tableRepresentation", "markdown");
        Object tableColumns = blockMetadata.get("tableColumns");
        if (tableColumns != null) {
            metadata.put("tableColumns", tableColumns);
        }
        Object tableRowCount = blockMetadata.get("tableRowCount");
        if (tableRowCount != null) {
            metadata.put("tableRowCount", tableRowCount);
        }
        Object links = blockMetadata.get("links");
        if (links != null) {
            metadata.put("links", links);
        }
        return metadata;
    }

    private Map<String, Object> tableRowMetadata(Map<String, Object> blockMetadata, Map<?, ?> rowMap) {
        Map<String, Object> metadata = new LinkedHashMap<>(rawTableMetadata(blockMetadata));
        Object rowIndex = rowMap.get("rowIndex");
        if (rowIndex != null) {
            metadata.put("tableRowIndex", rowIndex);
        }
        Object values = rowMap.get("values");
        if (values instanceof Map<?, ?> rowValues && !rowValues.isEmpty()) {
            metadata.put("tableRowValues", new LinkedHashMap<>(rowValues));
        }
        Object rowLinks = rowMap.get("links");
        if (rowLinks != null) {
            metadata.put("links", rowLinks);
        }
        metadata.put("tableRepresentation", "row-description");
        return metadata;
    }

    private String renderTableRowDescription(Map<?, ?> rowMap) {
        Object values = rowMap.get("values");
        if (!(values instanceof Map<?, ?> rowValues) || rowValues.isEmpty()) {
            return "";
        }

        List<String> segments = new ArrayList<>();
        for (Map.Entry<?, ?> entry : rowValues.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
            if (!value.isEmpty()) {
                segments.add(entry.getKey() + "=" + value);
            }
        }
        if (segments.isEmpty()) {
            return "";
        }
        return "表格行：" + String.join("; ", segments);
    }

    private void flushIfPresent(
            List<ChunkPiece> pieces,
            StringBuilder current,
            String prefix,
            String blockType,
            String codeLanguage,
            Map<String, Object> metadata
    ) {
        if (!current.isEmpty()) {
            pieces.add(new ChunkPiece(renderTextChunk(prefix, current.toString()), blockType, codeLanguage, metadata));
            current.setLength(0);
        }
    }

    private int approximateTokenCount(String text) {
        return tokenCounter.count(text);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /**
     * 单个结构化块拆分后的片段。
     *
     * @param text 片段正文
     * @param blockType 块类型
     * @param codeLanguage 代码语言
     * @param metadata 片段附加元数据
     */
    private record ChunkPiece(
            String text,
            String blockType,
            String codeLanguage,
            Map<String, Object> metadata
    ) {
    }
}
