package com.bytedance.ai.graph.catalog.service;

import com.bytedance.ai.shared.properties.RagProperties;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调 Doubao（或任意 Spring AI ChatModel）从商品描述抽出结构化属性 JSON。
 *
 * <p>构造路径与 {@code RagAnswerGenerator} 对齐：通过 {@link ObjectProvider} 延迟解析 ChatModel，
 * 让没有 LLM 凭据的环境（H2 单测、Native 编译期）也能正常加载 bean。
 *
 * <p>容错策略：
 * <ul>
 *   <li>ChatModel 不可用 → 抛 {@link LlmExtractionException}，由 worker 标 FAILED 并保留错误。</li>
 *   <li>结构化输出转换失败 → 抛 {@link LlmExtractionException}，由 worker 标 FAILED 并保留错误。</li>
 * </ul>
 */
@Component
public class LlmAttributeExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmAttributeExtractor.class);
    private static final ParameterizedTypeReference<Map<String, Object>> ATTRIBUTE_MAP_TYPE = new ParameterizedTypeReference<>() {
    };

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final RagProperties ragProperties;

    public LlmAttributeExtractor(
            ObjectProvider<ChatModel> chatModelProvider,
            RagProperties ragProperties
    ) {
        this.chatModelProvider = chatModelProvider;
        this.ragProperties = ragProperties;
    }

    /**
     * 对单条商品描述做一次属性抽取。
     *
     * @param description 商品长描述（建议传 SPU 的 Markdown 原文，至少几十字）
     * @return JSON 解析结果（保证非 null，可能为空 map）
     * @throws LlmExtractionException 当模型不可用、超时或解析失败
     */
    public Map<String, Object> extract(String description) {
        if (!StringUtils.hasText(description)) {
            log.debug("attribute extraction skipped because description is blank");
            return new LinkedHashMap<>();
        }
        ChatClient chatClient = resolveChatClient();
        String systemPrompt = ragProperties.catalog().attributeExtractionSystemPrompt();
        Map<String, Object> attributes;
        try {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt();
            if (StringUtils.hasText(systemPrompt)) {
                request = request.system(systemPrompt);
            }
            attributes = request
                    .user(description)
                    .call()
                    .entity(ATTRIBUTE_MAP_TYPE);
        } catch (RuntimeException exception) {
            log.warn(
                    "LLM attribute extraction call failed: error={}",
                    RagLogHelper.errorSummary(exception)
            );
            throw new LlmExtractionException("LLM 调用失败：" + exception.getMessage(), exception);
        }

        if (attributes == null) {
            throw new LlmExtractionException("LLM 返回空属性对象");
        }
        return new LinkedHashMap<>(attributes);
    }

    private ChatClient resolveChatClient() {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new LlmExtractionException("ChatModel 未配置，无法抽取商品属性");
        }
        return ChatClient.create(chatModel);
    }

    /**
     * 抽取流程的领域异常。
     */
    public static class LlmExtractionException extends RuntimeException {
        public LlmExtractionException(String message) {
            super(message);
        }

        public LlmExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
