package com.bytedance.ai.graph.catalog.service;

import com.bytedance.ai.shared.properties.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LlmAttributeExtractor 单元测试。
 *
 * <p>本测试不直接 mock Spring AI 的 ChatClient 链式调用（容易随 SDK 升级失稳），
 * 而是聚焦两条最关键的容错路径：
 * <ol>
 *   <li>ChatModel 未配置时，抛出领域异常以便 worker 标 FAILED；</li>
 *   <li>空描述短路返回空 map，不调用模型，避免浪费 RPM 配额。</li>
 * </ol>
 *
 * <p>parse() 的细节路径已经由实际接入 Doubao 后的集成验证覆盖。
 */
class LlmAttributeExtractorTests {

    private ObjectProvider<ChatModel> chatModelProvider;
    private LlmAttributeExtractor extractor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatModelProvider = (ObjectProvider<ChatModel>) mock(ObjectProvider.class);
        extractor = new LlmAttributeExtractor(
                chatModelProvider,
                RagProperties.defaults()
        );
    }

    @Test
    void blankDescriptionShortCircuitsWithoutInvokingModel() {
        when(chatModelProvider.getIfAvailable()).thenReturn(null);

        Map<String, Object> result = extractor.extract("   ");

        assertThat(result).isEmpty();
    }

    @Test
    void throwsDomainExceptionWhenChatModelUnavailable() {
        when(chatModelProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> extractor.extract("一段商品描述，足够长以避免空检"))
                .isInstanceOf(LlmAttributeExtractor.LlmExtractionException.class)
                .hasMessageContaining("ChatModel 未配置");
    }

    @Test
    void returnsStructuredAttributeMapFromChatClientEntity() {
        when(chatModelProvider.getIfAvailable()).thenReturn(new StubChatModel("""
                {"tags":["防水"],"usage_scenes":["通勤"],"target_audience":["学生"],"ingredients":[],"features":["轻量"]}
                """));

        Map<String, Object> result = extractor.extract("防水通勤双肩包，适合学生日常使用。");

        assertThat(result)
                .containsEntry("tags", List.of("防水"))
                .containsEntry("usage_scenes", List.of("通勤"))
                .containsEntry("target_audience", List.of("学生"))
                .containsEntry("features", List.of("轻量"));
    }

    private record StubChatModel(String content) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        }
    }
}
