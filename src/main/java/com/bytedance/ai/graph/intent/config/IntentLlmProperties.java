package com.bytedance.ai.graph.intent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 意图识别 LLM 配置属性。
 */
@Component
@ConfigurationProperties(prefix = "graph.agent.intent-llm")
public class IntentLlmProperties {

    private String promptTemplatePath = "prompts/main-intent-router-v1.txt";
    private Integer maxMemoryChars = 3000;

    private String baseUrl;
    private String ApiKey;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private Duration timeout;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getPromptTemplatePath() {
        return promptTemplatePath;
    }

    public void setPromptTemplatePath(String promptTemplatePath) {
        this.promptTemplatePath = promptTemplatePath;
    }

    public Integer getMaxMemoryChars() {
        return maxMemoryChars;
    }

    public void setMaxMemoryChars(Integer maxMemoryChars) {
        this.maxMemoryChars = maxMemoryChars;
    }

    public String getApiKey() {
        return ApiKey;
    }

    public void setApiKey(String apiKey) {
        ApiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
