package com.bytedance.ai.agent.answer;

import com.bytedance.ai.agent.api.AgentStreamEvent;
import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.agent.application.AgentSseEventFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CitationExtractor {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[#(\\d+)]");

    public Flux<AgentStreamEvent> toAnswerEvents(
            Flux<String> deltas,
            List<SpuCardView> cards,
            String correlationId,
            AgentSseEventFactory eventFactory
    ) {
        StringBuilder seenText = new StringBuilder();
        Set<Integer> emittedRefs = new HashSet<>();
        return deltas.concatMap(delta -> {
            seenText.append(delta);
            List<AgentStreamEvent> events = new ArrayList<>();
            events.add(eventFactory.answerDelta(correlationId, delta));
            Matcher matcher = CITATION_PATTERN.matcher(seenText);
            while (matcher.find()) {
                int ref = Integer.parseInt(matcher.group(1));
                if (emittedRefs.add(ref) && ref > 0 && cards != null && ref <= cards.size()) {
                    SpuCardView card = cards.get(ref - 1);
                    events.add(eventFactory.citation(correlationId, "#" + ref, card.spuId(), null));
                }
            }
            return Flux.fromIterable(events);
        });
    }
}
