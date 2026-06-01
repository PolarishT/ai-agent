package com.bytedance.ai.graph.intent.service;

import com.bytedance.ai.graph.intent.config.IntentLlmProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class MainIntentPromptFactory {

    private static final String USER_MESSAGE_PLACEHOLDER = "{{userMessage}}";
    private static final String CONVERSATION_MEMORY_PLACEHOLDER = "{{conversationMemory}}";

    private final IntentLlmProperties properties;

    private String template;

    public MainIntentPromptFactory(IntentLlmProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        String templatePath = properties.getPromptTemplatePath();

        if (!StringUtils.hasText(templatePath)) {
            throw new IllegalStateException("graph.agent.intent-llm.prompt-template-path must not be empty");
        }

        this.template = loadTemplate(templatePath);
        validateTemplate(this.template, templatePath);
    }

    public String build(String userMessage, String conversationMemory) {
        ensureInitialized();

        return template
                .replace(USER_MESSAGE_PLACEHOLDER, sanitizeUserMessage(userMessage))
                .replace(CONVERSATION_MEMORY_PLACEHOLDER, sanitizeConversationMemory(conversationMemory));
    }

    private String sanitizeUserMessage(String value) {
        if (value == null) {
            return "";
        }
        return value.strip();
    }

    private String sanitizeConversationMemory(String value) {
        if (value == null) {
            return "";
        }

        String stripped = value.strip();

        int maxChars = properties.getMaxMemoryChars() == null
                ? 3000
                : properties.getMaxMemoryChars();

        if (stripped.length() <= maxChars) {
            return stripped;
        }

        return stripped.substring(stripped.length() - maxChars);
    }

    private String loadTemplate(String templatePath) {
        try {
            ClassPathResource resource = new ClassPathResource(templatePath);

            if (!resource.exists()) {
                throw new IllegalStateException("Main intent prompt template not found: " + templatePath);
            }

            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load main intent prompt template: " + templatePath, exception);
        }
    }

    private void validateTemplate(String template, String templatePath) {
        if (!template.contains(USER_MESSAGE_PLACEHOLDER)) {
            throw new IllegalStateException(
                    "Main intent prompt template missing placeholder "
                            + USER_MESSAGE_PLACEHOLDER
                            + ": "
                            + templatePath
            );
        }

        if (!template.contains(CONVERSATION_MEMORY_PLACEHOLDER)) {
            throw new IllegalStateException(
                    "Main intent prompt template missing placeholder "
                            + CONVERSATION_MEMORY_PLACEHOLDER
                            + ": "
                            + templatePath
            );
        }
    }

    private void ensureInitialized() {
        if (template == null) {
            throw new IllegalStateException("Main intent prompt template has not been initialized");
        }
    }
}