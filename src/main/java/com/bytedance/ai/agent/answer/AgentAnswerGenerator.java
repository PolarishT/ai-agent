package com.bytedance.ai.agent.answer;

import com.bytedance.ai.agent.api.SpuCardView;
import com.bytedance.ai.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

@Service
public class AgentAnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(AgentAnswerGenerator.class);
    private static final String FALLBACK_PROMPT_TEMPLATE = """
            你是电商导购助手。仅依据下方【候选商品】回答用户。
            推荐商品时必须用 [#N] 标记，N 等于商品列表里的序号。
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final Resource promptTemplateResource;

    public AgentAnswerGenerator(
            ObjectProvider<ChatModel> chatModelProvider,
            @Value("classpath:prompts/agent-answer-v1.txt") Resource promptTemplateResource
    ) {
        this.chatModelProvider = chatModelProvider;
        this.promptTemplateResource = promptTemplateResource;
    }

    public Flux<String> generateStream(
            String message,
            List<SpuCardView> cards,
            Consumer<Boolean> generatedByModelCallback
    ) {
        if (cards == null || cards.isEmpty()) {
            generatedByModelCallback.accept(false);
            return Flux.just("目前没有完全匹配的商品。你可以补充预算、品牌、使用场景或必须具备的功能，我再帮你缩小范围。");
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            generatedByModelCallback.accept(false);
            return Flux.just(fallbackAnswer(message, cards));
        }
        try {
            Flux<String> stream = ChatClient.create(chatModel)
                    .prompt()
                    .system(promptTemplate())
                    .user(buildUserPrompt(message, cards))
                    .stream()
                    .content()
                    .doOnSubscribe(ignored -> generatedByModelCallback.accept(true))
                    .filter(StringUtils::hasText);
            return stream.switchIfEmpty(Flux.defer(() -> {
                        generatedByModelCallback.accept(false);
                        return Flux.just(fallbackAnswer(message, cards));
                    }))
                    .onErrorResume(exception -> {
                        log.warn("agent answer generation failed, falling back: error={}", RagLogHelper.errorSummary(exception));
                        generatedByModelCallback.accept(false);
                        return Flux.just(fallbackAnswer(message, cards));
                    });
        } catch (RuntimeException exception) {
            log.warn("agent answer generation setup failed, falling back: error={}", RagLogHelper.errorSummary(exception));
            generatedByModelCallback.accept(false);
            return Flux.just(fallbackAnswer(message, cards));
        }
    }

    private String promptTemplate() {
        try {
            return promptTemplateResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            log.warn("agent answer prompt resource unavailable, using fallback: error={}", RagLogHelper.errorSummary(exception));
            return FALLBACK_PROMPT_TEMPLATE;
        }
    }

    private String buildUserPrompt(String message, List<SpuCardView> cards) {
        StringBuilder builder = new StringBuilder();
        builder.append("【候选商品】\n");
        for (int i = 0; i < cards.size(); i++) {
            SpuCardView card = cards.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(nullToEmpty(card.title()))
                    .append("（")
                    .append(nullToEmpty(card.brand()))
                    .append("）")
                    .append(" - 价格：")
                    .append(priceText(card))
                    .append("，库存：")
                    .append(card.stock() == null ? "未知" : card.stock())
                    .append("\n   ")
                    .append(card.reasons().isEmpty() ? "" : card.reasons().getFirst())
                    .append("\n");
        }
        builder.append("\n【用户消息】\n").append(message);
        return builder.toString();
    }

    private String fallbackAnswer(String message, List<SpuCardView> cards) {
        StringBuilder builder = new StringBuilder();
        builder.append("根据“").append(message).append("”，我先推荐：");
        for (int i = 0; i < Math.min(cards.size(), 3); i++) {
            SpuCardView card = cards.get(i);
            if (i > 0) {
                builder.append("；");
            }
            builder.append(" [#").append(i + 1).append("] ")
                    .append(nullToEmpty(card.title()))
                    .append("，")
                    .append(priceText(card));
        }
        builder.append("。价格和库存以商品卡片为准。");
        return builder.toString();
    }

    private String priceText(SpuCardView card) {
        if (card.priceMin() == null && card.priceMax() == null) {
            return "价格未知";
        }
        if (card.priceMin() == null) {
            return "¥" + card.priceMax();
        }
        if (card.priceMax() == null || card.priceMin().compareTo(card.priceMax()) == 0) {
            return "¥" + card.priceMin();
        }
        return "¥" + card.priceMin() + "-¥" + card.priceMax();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
