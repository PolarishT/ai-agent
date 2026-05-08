package com.involutionhell.backend.rag.retrieval.service;

import com.involutionhell.backend.rag.retrieval.model.RagRetrievedChunk;
import com.involutionhell.backend.rag.retrieval.observability.RagRetrievalMetrics;
import com.involutionhell.backend.rag.retrieval.support.RagRequestFeedbacks;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * 基于检索到的上下文生成最终回答。
 */
@Service
public class RagAnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(RagAnswerGenerator.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RagRetrievalMetrics retrievalMetrics;
    private final RagProperties ragProperties;

    public RagAnswerGenerator(
            ObjectProvider<ChatModel> chatModelProvider,
            RagRetrievalMetrics retrievalMetrics,
            RagProperties ragProperties
    ) {
        this.chatModelProvider = chatModelProvider;
        this.retrievalMetrics = retrievalMetrics;
        this.ragProperties = ragProperties;
    }

    /**
     * 优先调用 ChatClient 生成答案；若未配置或调用失败，则回退到本地摘要。
     */
    public Flux<String> generateStream(
            String question,
            List<RagRetrievedChunk> contexts,
            Consumer<Boolean> generatedByModelCallback
    ) {
        if (contexts == null || contexts.isEmpty()) {
            log.debug("Streaming answer generation skipped because no context was retrieved: questionPreview={}", RagLogHelper.previewQuestion(question));
            retrievalMetrics.recordFallback("answer_generate", "answer", "no_context");
            RagRequestFeedbacks.record("answer_generate", "no_context", "未检索到可用上下文，已返回本地摘要。");
            generatedByModelCallback.accept(false);
            return Flux.just(fallback(question, contexts, null).answer());
        }
        ChatClient chatClient = resolveChatClient();
        if (chatClient == null) {
            log.debug("Streaming answer generation falling back because ChatClient is unavailable: contextCount={}", contexts.size());
            retrievalMetrics.recordFallback("answer_generate", "answer", "no_chat_model");
            RagRequestFeedbacks.record("answer_generate", "no_chat_model", "大模型不可用，已回退为检索摘要。");
            generatedByModelCallback.accept(false);
            return Flux.just(fallback(question, contexts, null).answer());
        }

        // Spring AI 的 stream().content() 已经返回 Flux<String>，这里仅补空响应、异常和总耗时保护。
        Flux<String> stream = chatClient.prompt()
                .system("""
                        你是一个后端 RAG 助手。
                        只能依据给定上下文回答问题，不要编造。
                        如果上下文不足，请明确说明“根据当前知识库无法确认”。
                        回答请使用简体中文，并尽量简洁。
                        """)
                .user(buildPrompt(question, contexts))
                .stream()
                .content()
                .doOnSubscribe(ignored -> generatedByModelCallback.accept(true))
                .filter(StringUtils::hasText);
        stream = applyAnswerTimeout(stream);
        return stream.switchIfEmpty(Flux.defer(() -> {
                    // 空流对前端等同于无回答，必须转为可见的 fallback delta。
                    log.warn("Streaming answer generation returned empty content, falling back to summary: contextCount={}", contexts.size());
                    retrievalMetrics.recordFallback("answer_generate", "answer", "empty_response");
                    RagRequestFeedbacks.record("answer_generate", "empty_response", "大模型返回空内容，已回退为检索摘要。");
                    generatedByModelCallback.accept(false);
                    return Flux.just(fallback(question, contexts, "OpenAI 调用失败，已回退为检索摘要。").answer());
                }))
                .onErrorResume(exception -> {
                    // SSE 不因模型异常直接中断，优先返回可解释的检索摘要并通过 notice 暴露降级。
                    log.warn("Streaming answer generation failed, falling back to summary: error={}", RagLogHelper.errorSummary(exception));
                    retrievalMetrics.recordFallback("answer_generate", "answer", "error");
                    RagRequestFeedbacks.record("answer_generate", "error", "大模型生成失败，已回退为检索摘要。");
                    generatedByModelCallback.accept(false);
                    return Flux.just(fallback(question, contexts, "大模型生成失败，已回退为检索摘要。").answer());
                });
    }

    public RagAnswerResult fallback(String question, List<RagRetrievedChunk> contexts, String notice) {
        String answer = contexts == null || contexts.isEmpty()
                ? "我没有检索到可用的知识片段，当前无法基于仓库数据回答这个问题。"
                : buildFallbackAnswer(question, contexts);
        if (StringUtils.hasText(notice)) {
            answer = answer + "\n\n[说明] " + notice;
        }
        return new RagAnswerResult(answer, false);
    }

    private ChatClient resolveChatClient() {
        try {
            ChatModel chatModel = chatModelProvider.getIfAvailable();
            return chatModel == null ? null : ChatClient.create(chatModel);
        } catch (Exception exception) {
            log.warn("ChatModel is unavailable, falling back to summary: error={}", RagLogHelper.errorSummary(exception));
            return null;
        }
    }

    private Flux<String> applyAnswerTimeout(Flux<String> stream) {
        long timeoutMillis = ragProperties.retrieval().answerGenerationTimeoutMillis();
        if (timeoutMillis <= 0) {
            return stream;
        }
        return stream.timeout(Duration.ofMillis(timeoutMillis));
    }

    private String buildPrompt(String question, List<RagRetrievedChunk> contexts) {
        log.debug(
                "Building answer prompt: questionLength={}, questionPreview={}, contextCount={}",
                question.length(),
                RagLogHelper.previewQuestion(question),
                contexts.size()
        );
        StringBuilder builder = new StringBuilder();
        builder.append("问题：").append(question).append("\n\n");
        builder.append("可用上下文：\n");
        for (int i = 0; i < contexts.size(); i++) {
            RagRetrievedChunk chunk = contexts.get(i);
            builder.append(i + 1)
                    .append(". [documentId=").append(chunk.documentId())
                    .append(", chunkIndex=").append(chunk.chunkIndex())
                    .append(", sourceUri=").append(chunk.sourceUri() == null ? "" : chunk.sourceUri())
                    .append(", title=").append(chunk.title() == null ? "" : chunk.title())
                    .append(", blockType=").append(chunk.blockType() == null ? "" : chunk.blockType())
                    .append(", headingPath=").append(chunk.headingPath() == null ? List.of() : chunk.headingPath())
                    .append("]\n")
                    .append(chunk.content())
                    .append("\n\n");
        }
        builder.append("请基于以上上下文作答，并在结尾简短指出主要依据来自哪些片段。");
        return builder.toString();
    }

    private String buildFallbackAnswer(String question, List<RagRetrievedChunk> contexts) {
        StringBuilder builder = new StringBuilder();
        builder.append("当前未启用大模型生成，以下是与问题“")
                .append(question)
                .append("”最相关的知识片段摘要：\n");
        for (RagRetrievedChunk chunk : contexts) {
            builder.append("- [documentId=")
                    .append(chunk.documentId())
                    .append(", chunkIndex=")
                    .append(chunk.chunkIndex())
                    .append(", sourceUri=")
                    .append(chunk.sourceUri() == null ? "" : chunk.sourceUri())
                    .append(", blockType=")
                    .append(chunk.blockType() == null ? "" : chunk.blockType())
                    .append(", headingPath=")
                    .append(chunk.headingPath() == null ? List.of() : chunk.headingPath())
                    .append("] ")
                    .append(chunk.content())
                    .append('\n');
        }
        return builder.toString().trim();
    }
}
