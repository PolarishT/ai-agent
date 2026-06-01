package com.bytedance.ai.graph.cartmanage.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 购物车管理槽位抽取提示词工厂。
 */
public final class CartManageSlotPromptFactory {

    private static final String TEMPLATE_RESOURCE = "prompts/cart-manage-slot-filling-v1.txt";
    private static final String USER_MESSAGE_PLACEHOLDER = "{{userMessage}}";
    private static final String CONVERSATION_MEMORY_PLACEHOLDER = "{{conversationMemory}}";
    private static final String TEMPLATE = loadTemplate();

    private CartManageSlotPromptFactory() {
    }

    public static String build(String userMessage, String conversationMemory) {
        return TEMPLATE
                .replace(USER_MESSAGE_PLACEHOLDER, nullToEmpty(userMessage))
                .replace(CONVERSATION_MEMORY_PLACEHOLDER, nullToEmpty(conversationMemory));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String loadTemplate() {
        ClassLoader classLoader = CartManageSlotPromptFactory.class.getClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException(
                        "Cart manage slot prompt template not found: " + TEMPLATE_RESOURCE);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to load cart manage slot prompt template: " + TEMPLATE_RESOURCE, exception);
        }
    }
}
