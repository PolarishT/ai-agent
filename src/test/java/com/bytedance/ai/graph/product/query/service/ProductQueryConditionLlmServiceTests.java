package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductQueryConditionLlmServiceTests {

    @Test
    @SuppressWarnings("unchecked")
    void parsesStructuredEntityAndKeepsSanitizerPath() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        ObjectProvider<MeterRegistry> meterRegistryProvider = (ObjectProvider<MeterRegistry>) mock(ObjectProvider.class);
        when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
        ProductQueryConditionLlmService service = new ProductQueryConditionLlmService(
                ChatClient.create(new StubChatModel("""
                        {
                          "rawQuery": "找轻便水杯 123456",
                          "normalizedQuery": "轻便水杯",
                          "intent": "QUERY",
                          "queryMode": "HYBRID",
                          "keywordQuery": "轻便水杯 123456",
                          "semanticQuery": "轻便水杯",
                          "categoryTerms": ["水杯"],
                          "excludeCategoryTerms": [],
                          "brandTerms": [],
                          "excludeBrandTerms": [],
                          "includeTerms": ["轻便", "123456"],
                          "excludeTerms": [],
                          "productId": "SHOULD_BE_STRIPPED",
                          "attributes": {
                            "color": {"include": [], "exclude": []},
                            "size": {"include": [], "exclude": []},
                            "material": {"include": [], "exclude": []},
                            "capacity": null
                          },
                          "priceMin": null,
                          "priceMax": null,
                          "mustHaveStock": null,
                          "sort": "RELEVANCE",
                          "refineType": "RESET",
                          "comparisonTargets": [],
                          "comparisonTargetTexts": ["水杯 A", "水杯 B"],
                          "compareFocus": ["通勤"],
                          "requestedDimensions": ["价格", "容量"],
                          "needComparison": false,
                          "confidence": 0.9,
                          "needClarify": false,
                          "missingSlots": []
                        }
                        """)),
                objectMapper,
                new ProductQueryConditionSanitizer(objectMapper, meterRegistryProvider)
        );

        ProductQueryCondition condition = service.parse("找轻便水杯", null, null);

        assertThat(condition.intent()).isEqualTo("QUERY");
        assertThat(condition.keywordQuery()).isEqualTo("轻便水杯");
        assertThat(condition.includeTerms()).containsExactly("轻便");
        assertThat(condition.categoryTerms()).containsExactly("水杯");
        assertThat(condition.comparisonTargetTexts()).containsExactly("水杯 A", "水杯 B");
        assertThat(condition.compareFocus()).containsExactly("通勤");
        assertThat(condition.requestedDimensions()).containsExactly("价格", "容量");
    }

    private record StubChatModel(String content) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        }
    }
}
