package com.bytedance.ai.agent.slot;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmSlotExtractorTests {

    private final LlmSlotExtractor extractor = new LlmSlotExtractor(
            noChatModel(),
            new RagJsonCodec(JsonMapper.builder().build())
    );

    @Test
    void fallbackExtractsBelowPriceSuffix() {
        Slot slot = extractor.extract("推荐 300 元以下的双肩包", IntentType.FILTER_BY_ATTR);

        assertThat(slot.priceRange()).isEqualTo(new Slot.PriceRange(null, new BigDecimal("300")));
        assertThat(slot.must()).isEmpty();
        assertThat(slot.brands()).isEmpty();
    }

    @Test
    void fallbackExtractsBelowPricePrefix() {
        Slot slot = extractor.extract("预算 500 块的耳机", IntentType.FILTER_BY_ATTR);

        assertThat(slot.priceRange()).isEqualTo(new Slot.PriceRange(null, new BigDecimal("500")));
    }

    @Test
    void fallbackExtractsAndNormalizesPriceRange() {
        Slot slot = extractor.extract("1000-500 元的行李箱", IntentType.FILTER_BY_ATTR);

        assertThat(slot.priceRange()).isEqualTo(new Slot.PriceRange(new BigDecimal("500"), new BigDecimal("1000")));
    }

    @Test
    void outOfScopeReturnsEmptySlot() {
        assertThat(extractor.extract("帮我写代码", IntentType.OUT_OF_SCOPE).isEmpty()).isTrue();
    }

    @Test
    void parseAcceptsJsonObjectInsideMarkdownFence() {
        Slot slot = extractor.parse("""
                ```json
                {"must":["轻便","防水"],"priceRange":{"min":100,"max":300},"categoryHint":"箱包","brands":["Acme"]}
                ```
                """);

        assertThat(slot.must()).containsExactly("轻便", "防水");
        assertThat(slot.priceRange()).isEqualTo(new Slot.PriceRange(new BigDecimal("100.0"), new BigDecimal("300.0")));
        assertThat(slot.categoryHint()).isEqualTo("箱包");
        assertThat(slot.brands()).containsExactly("Acme");
        assertThat(slot.mustNot()).isEmpty();
        assertThat(slot.scenario()).isNull();
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
