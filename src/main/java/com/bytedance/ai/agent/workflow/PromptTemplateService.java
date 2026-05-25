package com.bytedance.ai.agent.workflow;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class PromptTemplateService {

    private final ResourceLoader resourceLoader;

    public PromptTemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String load(String promptKey) {
        if (promptKey == null || promptKey.isBlank()) {
            return "";
        }
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/" + promptKey + ".txt");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Prompt template not found: " + promptKey, exception);
        }
    }
}
