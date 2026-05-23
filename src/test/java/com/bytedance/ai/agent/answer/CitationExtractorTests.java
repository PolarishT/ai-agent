package com.bytedance.ai.agent.answer;

import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.application.AgentSseEventFactory;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationExtractorTests {

    private final CitationExtractor extractor = new CitationExtractor();
    private final AgentSseEventFactory eventFactory = new AgentSseEventFactory();

    @Test
    void emitsAnswerDeltasAndCitationOnceWhenRefAppears() {
        List<AgentStreamEvent> events = extractor.toAnswerEvents(
                Flux.just("推荐 ", "[#1]", "，也可以看 ", "[#1]"),
                List.of(card(101L)),
                "corr-1",
                eventFactory
        ).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::event)
                .containsExactly("answer.delta", "answer.delta", "citation", "answer.delta", "answer.delta");
    }

    @Test
    void ignoresReferencesOutsideCardRange() {
        List<AgentStreamEvent> events = extractor.toAnswerEvents(
                Flux.just("看看 [#2]"),
                List.of(card(101L)),
                "corr-1",
                eventFactory
        ).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgentStreamEvent::event).containsExactly("answer.delta");
    }

    private SpuCardView card(Long spuId) {
        return new SpuCardView(
                spuId,
                "SPU-" + spuId,
                "商品" + spuId,
                "Acme",
                null,
                new BigDecimal("99"),
                new BigDecimal("99"),
                10,
                0.9d,
                List.of(),
                List.of(),
                "#1"
        );
    }
}
