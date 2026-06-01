package com.bytedance.ai.graph.cartmanage.application;

import com.bytedance.ai.graph.cartmanage.CartManageSlots;
import com.bytedance.ai.graph.cartmanage.CartManageAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-driven slot filling for cart_manage_workflow.
 *
 * <p>Reuses the {@code intentChatClient} bean (no separate cart-specific client per spec). On
 * parse failure we fall back to {@link CartManageSlots#unknown(String)} so the workflow can still
 * return WAITING_CLARIFICATION instead of bubbling an exception.
 */
@Service
public class CartManageSlotFillingService {

    private static final Logger log = LoggerFactory.getLogger(CartManageSlotFillingService.class);
    private static final List<Pattern> PRICE_PATTERNS = List.of(
            Pattern.compile("(?:商品)?价格\\s*(?:为|是|=|：|:)?\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Pattern.compile("(?:预算|价位)\\s*(?:为|是|=|：|:)?\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Pattern.compile("[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*元\\s*(?:的那个|那个|这款|的)?")
    );

    private final ChatClient intentChatClient;

    public CartManageSlotFillingService(@Qualifier("intentChatClient") ChatClient intentChatClient) {
        this.intentChatClient = intentChatClient;
    }

    public CartManageSlots extract(String userMessage, String conversationMemory) {
        String prompt = CartManageSlotPromptFactory.build(userMessage, conversationMemory);
        try {
            CartManageSlots raw = intentChatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(CartManageSlots.class);
            return normalize(raw, userMessage);
        } catch (Exception exception) {
            log.atWarn()
                    .addKeyValue("event.name", "cart_manage.slot_filling.failed")
                    .addKeyValue("event.outcome", "failure")
                    .setCause(exception)
                    .log("cart manage slot filling failed; falling back to UNKNOWN");
            return unknownWithDeterministicPrice(
                    "slot filling LLM call failed: " + exception.getMessage(), userMessage);
        }
    }

    private CartManageSlots normalize(CartManageSlots raw, String userMessage) {
        if (raw == null) {
            return unknownWithDeterministicPrice("slot filling LLM returned null", userMessage);
        }
        CartManageAction action = raw.action() == null ? CartManageAction.UNKNOWN : raw.action();
        Boolean contextualReference = raw.contextualReference() == null ? Boolean.FALSE : raw.contextualReference();
        Integer itemIndex = raw.itemIndex();
        if (itemIndex != null && itemIndex < 1) {
            itemIndex = null;
        }
        String productName = blankToNull(raw.productName());
        String productId = blankToNull(raw.productId());
        String skuId = blankToNull(raw.skuId());
        Integer quantity = raw.quantity();
        BigDecimal expectedPrice = raw.expectedPrice() == null
                ? extractExpectedPrice(userMessage)
                : raw.expectedPrice();
        String reason = raw.reason() == null ? "" : raw.reason();
        return new CartManageSlots(action, itemIndex, productName, productId, skuId, quantity,
                contextualReference, expectedPrice, reason);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private CartManageSlots unknownWithDeterministicPrice(String reason, String userMessage) {
        return new CartManageSlots(CartManageAction.UNKNOWN, null, null, null, null, null,
                false, extractExpectedPrice(userMessage), reason);
    }

    private BigDecimal extractExpectedPrice(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        for (Pattern pattern : PRICE_PATTERNS) {
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                try {
                    return new BigDecimal(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
