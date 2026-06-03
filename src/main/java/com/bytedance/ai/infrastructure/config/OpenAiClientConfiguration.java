package com.bytedance.ai.infrastructure.config;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Explicit OpenAI-compatible model wiring.
 *
 * <p>Local E2E uses separate providers: DashScope-compatible embeddings and Volcengine
 * Ark-compatible chat. These beans keep chat and embedding clients isolated instead of relying on
 * the common {@code spring.ai.openai.*} endpoint.
 */
@Configuration
public class OpenAiClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClientConfiguration.class);

    private static void requireText(String value, String propertyName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(propertyName + " must not be blank when configuring OpenAI-compatible client");
        }
    }

    private static String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String safeBaseUrl(String value) {
        return StringUtils.hasText(value) ? trimTrailingSlash(value) : "<blank>";
    }

    private static String chatCompletionsBaseUrl(String value) {
        String trimmed = trimTrailingSlash(value);
        String arkSuffix = "/api/v3/chat/completions";
        String openAiSuffix = "/v1/chat/completions";
        if (StringUtils.hasText(trimmed) && trimmed.endsWith(arkSuffix)) {
            return trimmed.substring(0, trimmed.length() - arkSuffix.length());
        }
        if (StringUtils.hasText(trimmed) && trimmed.endsWith(openAiSuffix)) {
            return trimmed.substring(0, trimmed.length() - openAiSuffix.length());
        }
        return trimmed;
    }

    private static RetryTemplate retryTemplate(Integer maxRetries) {
        int retries = maxRetries == null || maxRetries < 0 ? 3 : maxRetries;
        return new RetryTemplate(RetryPolicy.withMaxRetries(retries));
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "spring.ai.openai.chat", name = "api-key")
    public ChatModel chatModel(
            @Value("${spring.ai.openai.chat.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.model:gpt-4o-mini}") String model,
            @Value("${spring.ai.openai.chat.temperature:0.2}") Double temperature,
            @Value("${spring.ai.openai.chat.max-retries:3}") Integer maxRetries,
            ObjectProvider<ObservationRegistry> observationRegistry
    ) {
        requireText(apiKey, "spring.ai.openai.chat.api-key");
        requireText(baseUrl, "spring.ai.openai.chat.base-url");
        requireText(model, "spring.ai.openai.chat.model");

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        log.info("OpenAI-compatible chat client configured: providerBaseUrl={}, model={}", safeBaseUrl(baseUrl), model);
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .retryTemplate(retryTemplate(maxRetries))
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "spring.ai.openai.embedding", name = "api-key")
    public EmbeddingModel embeddingModel(
            @Value("${spring.ai.openai.embedding.api-key}") String apiKey,
            @Value("${spring.ai.openai.embedding.base-url}") String baseUrl,
            @Value("${spring.ai.openai.embedding.model}") String model,
            @Value("${spring.ai.openai.embedding.dimensions}") Integer dimensions,
            @Value("${spring.ai.openai.embedding.max-retries:3}") Integer maxRetries,
            ObjectProvider<ObservationRegistry> observationRegistry
    ) {
        requireText(apiKey, "spring.ai.openai.embedding.api-key");
        requireText(baseUrl, "spring.ai.openai.embedding.base-url");
        requireText(model, "spring.ai.openai.embedding.model");

        OpenAiEmbeddingOptions.Builder builder = OpenAiEmbeddingOptions.builder()
                .model(model);
        if (dimensions != null && dimensions > 0) {
            builder.dimensions(dimensions);
        }

        log.info(
                "OpenAI-compatible embedding client configured: providerBaseUrl={}, model={}, dimensions={}",
                safeBaseUrl(baseUrl),
                model,
                dimensions == null || dimensions <= 0 ? "<provider-default>" : dimensions
        );
        return new OpenAiEmbeddingModel(
                OpenAiApi.builder()
                        .apiKey(apiKey)
                        .baseUrl(trimTrailingSlash(baseUrl))
                        .build(),
                MetadataMode.EMBED,
                builder.build(),
                retryTemplate(maxRetries),
                observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP)
        );
    }

    @Bean(name = "intentChatClient")
    public ChatClient intentChatClient(
            @Value("${graph.agent.intent-llm.api-key}") String apiKey,
            @Value("${graph.agent.intent-llm.base-url}") String baseUrl,
            @Value("${graph.agent.intent-llm.model}") String modelName,
            @Value("${graph.agent.intent-llm.temperature:0}") Double temperature,
            @Value("${graph.agent.intent-llm.max-tokens:512}") Integer maxTokens,
            @Value("${graph.agent.intent-llm.timeout:10s}") Duration timeout
    ) {
        requireText(apiKey, "graph.agent.intent-llm.api-key");
        requireText(baseUrl, "graph.agent.intent-llm.base-url");
        requireText(modelName, "graph.agent.intent-llm.model");

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(chatCompletionsBaseUrl(baseUrl))

                .apiKey(apiKey)
                .build();

        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();

        log.info("Intent LLM ChatClient configured: model={}, temperature={}, maxTokens={}, timeout={}",
                modelName,
                temperature,
                maxTokens,
                timeout);
        return ChatClient.builder(model)
                .build();
    }
}
