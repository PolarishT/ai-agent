package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.shared.metadata.RagSearchFilter;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DefaultRagRetrievalBudgetPlanner implements RagRetrievalBudgetPlanner {

    private static final Pattern CODE_OR_PATH_PATTERN = Pattern.compile("[`{}()\\[\\];=<>/\\\\._:-]");
    private static final Pattern ASCII_WORDS_PATTERN = Pattern.compile("^[\\p{Alnum}\\s`{}()\\[\\];=<>/\\\\._:-]+$");

    private final RagProperties ragProperties;

    public DefaultRagRetrievalBudgetPlanner(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public RagRetrievalBudget plan(
            String originalQuestion,
            List<String> retrievalQueries,
            RagSearchFilter filter,
            int requestedTopK,
            boolean isRetry
    ) {
        int answerTopK = Math.max(1, requestedTopK);
        int queryCount = Math.max(1, retrievalQueries == null ? 0 : retrievalQueries.size());
        RagProperties.Retrieval retrieval = ragProperties.retrieval();

        int baseMultiplier = queryCount == 1
                ? retrieval.adaptiveSingleQueryJoinCandidateMultiplier()
                : retrieval.adaptiveMultiQueryJoinCandidateMultiplier();
        int maxTotalBudget = isRetry
                ? retrieval.progressiveMaxTotalJoinCandidateBudget()
                : retrieval.maxTotalJoinCandidateBudget();
        int effectiveMultiplier = isRetry ? baseMultiplier * 2 : baseMultiplier;
        int totalJoinCandidateBudget = Math.min(
                Math.max(answerTopK * effectiveMultiplier, answerTopK),
                Math.max(answerTopK, maxTotalBudget)
        );
        int perQueryTopK = Math.max(1, totalJoinCandidateBudget / queryCount);

        boolean hasFilter = filter != null && !filter.isEmpty();
        QueryShape shape = detectShape(originalQuestion, retrievalQueries);
        int semanticMultiplier = hasFilter
                ? retrieval.semanticFilteredCandidateMultiplier()
                : retrieval.semanticCandidateMultiplier();
        int keywordMultiplier = retrieval.keywordCandidateMultiplier();
        List<String> reasons = new ArrayList<>();
        reasons.add(isRetry ? "retry" : "initial");
        reasons.add(queryCount == 1 ? "single_query" : "multi_query");

        if (hasFilter) {
            semanticMultiplier++;
            reasons.add("filter_semantic_boost");
        }
        if (shape.keywordLeaning()) {
            keywordMultiplier++;
            reasons.add("keyword_shape_boost");
        }
        if (shape.semanticLeaning()) {
            semanticMultiplier++;
            reasons.add("semantic_shape_boost");
        }
        if (isRetry) {
            semanticMultiplier++;
            keywordMultiplier++;
            reasons.add("progressive_widening");
        }

        int semanticCandidateTopK = Math.min(
                Math.max(perQueryTopK * semanticMultiplier, perQueryTopK),
                retrieval.semanticCandidateTopKMax()
        );
        int keywordCandidateTopK = Math.min(
                Math.max(perQueryTopK * keywordMultiplier, perQueryTopK),
                retrieval.keywordCandidateTopKMax()
        );

        return new RagRetrievalBudget(
                answerTopK,
                queryCount,
                perQueryTopK,
                semanticCandidateTopK,
                keywordCandidateTopK,
                retrieval.progressiveWideningEnabled() && !isRetry,
                String.join(",", reasons)
        );
    }

    private QueryShape detectShape(String originalQuestion, List<String> retrievalQueries) {
        String joined = String.join(" ", retrievalQueries == null ? List.of() : retrievalQueries);
        String text = StringUtils.hasText(joined) ? joined : originalQuestion;
        String normalized = text == null ? "" : text.trim();
        int length = normalized.length();
        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean asciiWords = ASCII_WORDS_PATTERN.matcher(normalized).matches();
        boolean hasCodeOrPath = CODE_OR_PATH_PATTERN.matcher(normalized).find()
                || lower.contains("exception")
                || lower.contains("error")
                || lower.contains("class ")
                || lower.contains("method ")
                || lower.contains(" api");
        boolean shortPhrase = length > 0 && length <= 48;
        boolean longNaturalLanguage = length >= 40 && !hasCodeOrPath;
        return new QueryShape((shortPhrase && asciiWords) || hasCodeOrPath, longNaturalLanguage);
    }

    private record QueryShape(boolean keywordLeaning, boolean semanticLeaning) {
    }
}
