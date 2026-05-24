package com.bytedance.ai.agent.intent;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.memory.ConversationMemory;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LlmIntentClassifierTests {

    private final RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
    private final LlmIntentClassifier classifier = new LlmIntentClassifier(noChatModel(), jsonCodec);

    @Test
    void parsesStrictJsonAsLlmIntent() {
        IntentClassification classification = classifier.parse("{\"intent\":\"COMPARE\",\"confidence\":0.91}");

        assertThat(classification.intent()).isEqualTo(IntentType.COMPARE);
        assertThat(classification.confidence()).isEqualTo(0.91d);
        assertThat(classification.source()).isEqualTo("llm");
    }

    @Test
    void parsesJsonObjectFromModelText() {
        IntentClassification classification = classifier.parse("""
                ```json
                {"intent":"CART_OP","confidence":1.4}
                ```
                """);

        assertThat(classification.intent()).isEqualTo(IntentType.CART_OP);
        assertThat(classification.confidence()).isEqualTo(1d);
        assertThat(classification.source()).isEqualTo("llm");
    }

    @Test
    void fallsBackWhenChatModelIsUnavailable() {
        assertIntent("帮我写代码实现快排", IntentType.OUT_OF_SCOPE, "fallback");
        assertIntent("推荐 300 元以下的双肩包", IntentType.FILTER_BY_ATTR, "fallback");
        assertIntent("A vs B 哪个保湿", IntentType.COMPARE, "fallback");
        assertIntent("把刚才那款加到购物车", IntentType.CART_OP, "fallback");
    }

    @Test
    void fallbackRefineStillRequiresPreviousCards() {
        ConversationMemory memory = new ConversationMemory(
                List.of(),
                Optional.empty(),
                null,
                null,
                List.of("SPU-9"),
                Optional.empty(),
                Optional.empty()
        );

        IntentClassification refine = classifier.classify("这些里面再便宜一点", memory);
        IntentClassification noMemory = classifier.classify("这些里面再便宜一点", ConversationMemory.empty());

        assertThat(refine.intent()).isEqualTo(IntentType.REFINE);
        assertThat(refine.source()).isEqualTo("fallback");
        assertThat(noMemory.intent()).isNotEqualTo(IntentType.REFINE);
    }

    private void assertIntent(String message, IntentType intent, String source) {
        IntentClassification classification = classifier.classify(message);
        assertThat(classification.intent()).isEqualTo(intent);
        assertThat(classification.source()).isEqualTo(source);
    }

    private static ObjectProvider<ChatModel> noChatModel() {
        return new ObjectProvider<>() {
            @Override
            public ChatModel getObject(Object... args) throws BeansException {
                return null;
            }

            @Override
            public ChatModel getIfAvailable() throws BeansException {
                return null;
            }

            @Override
            public ChatModel getIfUnique() throws BeansException {
                return null;
            }

            @Override
            public ChatModel getObject() throws BeansException {
                return null;
            }
        };
    }
}
