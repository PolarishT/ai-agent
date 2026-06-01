package com.bytedance.ai.graph.cartmanage.subgraph.support;

import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.cartmanage.subgraph.CandidateSelectionLlmService;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 候选商品选择协调器，结合规则匹配和 LLM 选择结果。
 */
public class CandidateSelectionResolver {

    private static final Pattern SELECTION_PATTERN = Pattern.compile(
            "^\\s*(?:我)?\\s*(?:选|选择|要|想要|就)?\\s*(?:第)?\\s*([1-5一二三四五])\\s*(?:个|款|号)?\\s*(?:吧)?\\s*$"
    );

    private final CandidateSelectionLlmService candidateSelectionLlmService;

    public CandidateSelectionResolver(CandidateSelectionLlmService candidateSelectionLlmService) {
        this.candidateSelectionLlmService = candidateSelectionLlmService;
    }

    public CandidateSelectionResult resolve(String message, List<ProductCandidate> candidates) {
        int candidateCount = candidates == null ? 0 : candidates.size();
        int index = parseSelectionIndex(message, candidateCount);
        if (isValidCandidateIndex(index, candidateCount)) {
            return CandidateSelectionResult.selected(index);
        }

        CandidateSelectionResult attributeSelection = attributeMatch(message, candidates);
        if (attributeSelection.status() != CandidateSelectionStatus.UNMATCHED) {
            return attributeSelection;
        }

        if (candidateSelectionLlmService == null) {
            return CandidateSelectionResult.unmatched();
        }
        Optional<Integer> llmIndex = candidateSelectionLlmService.resolveIndex(message, candidates);
        if (llmIndex.isPresent() && isValidCandidateIndex(llmIndex.get(), candidateCount)) {
            return CandidateSelectionResult.selected(llmIndex.get());
        }
        return CandidateSelectionResult.unmatched();
    }

    public CandidateSelectionResult attributeMatch(String message, List<ProductCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return CandidateSelectionResult.unmatched();
        }
        List<String> tokens = candidateSelectionTokens(message);
        if (tokens.isEmpty()) {
            return CandidateSelectionResult.unmatched();
        }
        List<Integer> matchedIndexes = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String candidateText = normalizedCandidateText(candidates.get(i));
            if (tokens.stream().anyMatch(candidateText::contains)) {
                matchedIndexes.add(i + 1);
            }
        }
        if (matchedIndexes.size() == 1) {
            return CandidateSelectionResult.selected(matchedIndexes.getFirst());
        }
        if (matchedIndexes.size() > 1) {
            return CandidateSelectionResult.ambiguous();
        }
        return CandidateSelectionResult.unmatched();
    }

    public int parseSelectionIndex(String message, int candidateCount) {
        if (isImplicitThisSelection(message)) {
            return candidateCount == 1 ? 1 : -1;
        }
        if (message == null) {
            return -1;
        }
        Matcher matcher = SELECTION_PATTERN.matcher(message.trim());
        if (matcher.matches()) {
            Integer index = parseOneToFive(matcher.group(1));
            if (index != null) {
                return index;
            }
        }
        return -1;
    }

    public boolean looksLikeCandidateSelection(String message) {
        return looksLikeCandidateSelection(message, List.of());
    }

    public boolean looksLikeCandidateSelection(String message, List<ProductCandidate> candidates) {
        if (message == null) {
            return false;
        }
        return isImplicitThisSelection(message)
                || parseSelectionIndex(message, Math.max(1, candidates == null ? 5 : candidates.size())) > 0
                || (candidates != null && attributeMatch(message, candidates).status() != CandidateSelectionStatus.UNMATCHED);
    }

    private boolean isImplicitThisSelection(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.trim();
        return "就这个".equals(normalized)
                || "就要这个".equals(normalized)
                || "要这个".equals(normalized)
                || "这个".equals(normalized);
    }

    private Integer parseOneToFive(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return switch (value.trim()) {
            case "1", "一" -> 1;
            case "2", "二" -> 2;
            case "3", "三" -> 3;
            case "4", "四" -> 4;
            case "5", "五" -> 5;
            default -> null;
        };
    }

    private boolean isValidCandidateIndex(int index, int candidateCount) {
        return index >= 1 && index <= candidateCount;
    }

    private List<String> candidateSelectionTokens(String message) {
        String normalized = CartGraphStateSupport.normalizeMatchText(message);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        String cleaned = normalized
                .replace("我想要", "")
                .replace("我要", "")
                .replace("我选择", "")
                .replace("我选", "")
                .replace("选择", "")
                .replace("选", "")
                .replace("就要", "")
                .replace("要", "")
                .replace("那个", "")
                .replace("这个", "")
                .replace("这款", "")
                .replace("的", "")
                .replace("款", "")
                .replace("号", "");
        List<String> tokens = new ArrayList<>();
        addSelectionToken(tokens, cleaned);
        if (cleaned.endsWith("色") && cleaned.length() > 1) {
            addSelectionToken(tokens, cleaned.substring(0, cleaned.length() - 1));
        }
        return tokens;
    }

    private void addSelectionToken(List<String> tokens, String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }
        String trimmed = token.trim();
        if (trimmed.length() >= 2 && !"一个".equals(trimmed) && !"第二".equals(trimmed)) {
            tokens.add(trimmed);
        }
    }

    private String normalizedCandidateText(ProductCandidate candidate) {
        if (candidate == null) {
            return "";
        }
        return CartGraphStateSupport.normalizeMatchText(String.join(" ",
                CartGraphStateSupport.nullToEmpty(candidate.productName()),
                CartGraphStateSupport.nullToEmpty(candidate.spec()),
                CartGraphStateSupport.nullToEmpty(candidate.brief()),
                CartGraphStateSupport.nullToEmpty(candidate.externalRef()),
                CartGraphStateSupport.nullToEmpty(candidate.productId()),
                CartGraphStateSupport.nullToEmpty(candidate.skuId())
        ));
    }

    public enum CandidateSelectionStatus {
        SELECTED,
        AMBIGUOUS,
        UNMATCHED
    }

    public record CandidateSelectionResult(CandidateSelectionStatus status, int selectedIndex) {
        public static CandidateSelectionResult selected(int selectedIndex) {
            return new CandidateSelectionResult(CandidateSelectionStatus.SELECTED, selectedIndex);
        }

        public static CandidateSelectionResult ambiguous() {
            return new CandidateSelectionResult(CandidateSelectionStatus.AMBIGUOUS, -1);
        }

        public static CandidateSelectionResult unmatched() {
            return new CandidateSelectionResult(CandidateSelectionStatus.UNMATCHED, -1);
        }
    }
}
