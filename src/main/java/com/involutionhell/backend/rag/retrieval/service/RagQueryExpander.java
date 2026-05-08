package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.retrieval.observability.RagRetrievalMetrics;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 使用 Spring AI 官方的 MultiQueryExpander 替换掉手写的启发式拆解代码。
 * 保留现有 RagQueryExpansionResult 返回格式以对齐当前 retrieval 模块契约。
 */
@Service
public class RagQueryExpander {

    private static final Logger log = LoggerFactory.getLogger(RagQueryExpander.class);

    private final RagProperties ragProperties;
    private final MultiQueryExpander multiQueryExpander;
    private final RagRetrievalMetrics retrievalMetrics;

    public RagQueryExpander(
            ObjectProvider<MultiQueryExpander> multiQueryExpanderProvider,
            RagProperties ragProperties,
            RagRetrievalMetrics retrievalMetrics
    ) {
        this.ragProperties = ragProperties;
        this.retrievalMetrics = retrievalMetrics;
        this.multiQueryExpander = resolveMultiQueryExpander(multiQueryExpanderProvider);
    }

    private MultiQueryExpander resolveMultiQueryExpander(ObjectProvider<MultiQueryExpander> multiQueryExpanderProvider) {
        try {
            return multiQueryExpanderProvider.getIfAvailable();
        } catch (Exception exception) {
            log.warn("MultiQueryExpander Bean unavailable, expansion disabled for this request: error={}", RagLogHelper.errorSummary(exception));
            return null;
        }
    }

    /**
     * 将问题传递给官方组件进行扩展，并兼容封装为原有业务模型。
     */
    public RagQueryExpansionResult expand(String question) {
        String normalizedQuestion = question == null ? "" : question.trim();

        if (!ragProperties.queryExpansion().enabled() || this.multiQueryExpander == null || normalizedQuestion.isEmpty()) {
            log.debug("Query expansion skipped, using original query.");
            return new RagQueryExpansionResult(normalizedQuestion, List.of(normalizedQuestion), false, false);
        }

        try {
            // 向官方组件请求解析
            List<Query> springAiQueries = multiQueryExpander.expand(new Query(normalizedQuestion));
            List<String> textQueries = springAiQueries.stream()
                    .map(Query::text)
                    .map(String::trim)
                    .filter(text -> !text.isEmpty())
                    .distinct()
                    .toList();
            if (textQueries.isEmpty()) {
                log.warn("MultiQueryExpander returned empty result, falling back to original query.");
                retrievalMetrics.recordFallback("query_expand", "multi_query", "empty_result");
                return new RagQueryExpansionResult(normalizedQuestion, List.of(normalizedQuestion), false, false);
            }

            boolean isExpanded = textQueries.size() > 1 || !textQueries.getFirst().equals(normalizedQuestion);
            log.debug(
                    "Query expansion completed: originalPreview={}, queryCount={}, expanded={}, expandedQueries={}",
                    RagLogHelper.previewQuestion(normalizedQuestion),
                    textQueries.size(),
                    isExpanded,
                    textQueries.stream().map(RagLogHelper::previewQuestion).toList()
            );

            return new RagQueryExpansionResult(normalizedQuestion, textQueries, isExpanded, true);
        } catch (Exception exception) {
            log.warn("MultiQueryExpander failed, falling back to original query: error={}", RagLogHelper.errorSummary(exception));
            retrievalMetrics.recordFallback("query_expand", "multi_query", "error");
            return new RagQueryExpansionResult(normalizedQuestion, List.of(normalizedQuestion), false, false);
        }
    }
}
