package com.bytedance.ai.graph.orchestration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.conversation.ConversationMessage;
import com.bytedance.ai.graph.conversation.context.ConversationContextManager;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.conversation.context.RuntimeContextView;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Helpers for reading conversation context across graph state boundaries.
 */
public final class GuideGraphContextSupport {

    private GuideGraphContextSupport() {
    }

    public static ConversationRuntimeContext loadContext(
            ConversationContextManager conversationContextManager,
            OverAllState state
    ) {
        if (conversationContextManager == null) {
            return null;
        }
        String userId = state.value(GuideGraphStateKeys.USER_ID, "");
        String conversationId = state.value(GuideGraphStateKeys.CONVERSATION_ID, "");
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(conversationId)) {
            return null;
        }
        return conversationContextManager.load(userId, conversationId);
    }

    public static String conversationMemory(
            ConversationContextManager conversationContextManager,
            OverAllState state
    ) {
        ConversationRuntimeContext context = loadContext(conversationContextManager, state);
        if (context != null) {
            return context.conversationMemoryText();
        }
        return conversationMemoryFromState(state);
    }

    public static String conversationMemoryFromState(OverAllState state) {
        String memory = state.value(GuideGraphStateKeys.CONVERSATION_MEMORY, "");
        if (StringUtils.hasText(memory)) {
            return memory;
        }
        List<?> recentMessages = state.value(GuideGraphStateKeys.RECENT_MESSAGES, List.of());
        if (recentMessages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object value : recentMessages) {
            appendMessage(builder, value);
        }
        return builder.toString().trim();
    }

    private static void appendMessage(StringBuilder builder, Object value) {
        if (value instanceof RuntimeContextView.MessageView message) {
            appendMessage(builder, message.role(), message.content());
            return;
        }
        if (value instanceof ConversationMessage message) {
            appendMessage(builder, message.role(), message.content());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            appendMessage(builder, stringValue(map.get("role")), stringValue(map.get("content")));
        }
    }

    private static void appendMessage(StringBuilder builder, String role, String content) {
        if (!StringUtils.hasText(role) || !StringUtils.hasText(content)) {
            return;
        }
        builder.append(role).append(": ").append(content).append('\n');
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
