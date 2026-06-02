package com.bytedance.ai.graph.answer;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Answer 生成提示词工厂。装载模板 + 占位替换 + 长字段裁剪。
 */
@Component
public class AnswerPromptFactory {

    private static final String P_USER_MESSAGE = "{{userMessage}}";
    private static final String P_INTENT = "{{intent}}";
    private static final String P_WORKFLOW = "{{workflow}}";
    private static final String P_WORKFLOW_RESULT_JSON = "{{workflowResultJson}}";
    private static final String P_AGENT_MEMORY_TEXT = "{{agentMemoryText}}";
    private static final String P_RULE_FALLBACK = "{{ruleFallback}}";

    private static final int DEFAULT_MEMORY_MAX_CHARS = 4000;
    private static final int DEFAULT_WORKFLOW_RESULT_MAX_CHARS = 3000;

    private final String templatePath;
    private final int memoryMaxChars;
    private final int workflowResultMaxChars;

    private String template;

    public AnswerPromptFactory(
            @Value("${graph.agent.answer-llm.prompt-template-path:prompts/answer-generation-v1.txt}")
            String templatePath,
            @Value("${graph.agent.answer-llm.max-memory-chars:" + DEFAULT_MEMORY_MAX_CHARS + "}")
            int memoryMaxChars,
            @Value("${graph.agent.answer-llm.max-workflow-result-chars:"
                    + DEFAULT_WORKFLOW_RESULT_MAX_CHARS + "}")
            int workflowResultMaxChars
    ) {
        this.templatePath = templatePath;
        this.memoryMaxChars = memoryMaxChars > 0 ? memoryMaxChars : DEFAULT_MEMORY_MAX_CHARS;
        this.workflowResultMaxChars = workflowResultMaxChars > 0
                ? workflowResultMaxChars : DEFAULT_WORKFLOW_RESULT_MAX_CHARS;
    }

    @PostConstruct
    public void init() {
        if (!StringUtils.hasText(templatePath)) {
            throw new IllegalStateException(
                    "graph.agent.answer-llm.prompt-template-path must not be empty");
        }
        this.template = loadTemplate(templatePath);
        validateTemplate(this.template, templatePath);
    }

    public String build(AnswerPromptInput input) {
        ensureInitialized();
        return template
                .replace(P_USER_MESSAGE, safe(input.userMessage()))
                .replace(P_INTENT, safe(input.intent()))
                .replace(P_WORKFLOW, safe(input.workflow()))
                .replace(P_WORKFLOW_RESULT_JSON,
                        truncateTail(safe(input.workflowResultJson()), workflowResultMaxChars))
                .replace(P_AGENT_MEMORY_TEXT,
                        truncateHead(safe(input.agentMemoryText()), memoryMaxChars))
                .replace(P_RULE_FALLBACK, safe(input.ruleFallback()));
    }

    /** 入参载体；任何字段允许 null/空，工厂内部统一兜空字符串。 */
    public record AnswerPromptInput(
            String userMessage,
            String intent,
            String workflow,
            String workflowResultJson,
            String agentMemoryText,
            String ruleFallback
    ) {
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }

    /** 砍掉尾部，保留头部（适合 workflow result，靠前字段更关键）。 */
    private String truncateTail(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...<truncated>";
    }

    /** 砍掉头部，保留尾部（适合 memory，最近的更关键）。 */
    private String truncateHead(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return "<truncated>..." + value.substring(value.length() - max);
    }

    private String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                throw new IllegalStateException("Answer prompt template not found: " + path);
            }
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load answer prompt template: " + path, ex);
        }
    }

    private void validateTemplate(String tmpl, String path) {
        for (String placeholder : new String[]{
                P_USER_MESSAGE, P_INTENT, P_WORKFLOW,
                P_WORKFLOW_RESULT_JSON, P_AGENT_MEMORY_TEXT, P_RULE_FALLBACK
        }) {
            if (!tmpl.contains(placeholder)) {
                throw new IllegalStateException(
                        "Answer prompt template missing placeholder " + placeholder + ": " + path);
            }
        }
    }

    private void ensureInitialized() {
        if (template == null) {
            throw new IllegalStateException("Answer prompt template has not been initialized");
        }
    }
}
