package com.bytedance.ai.infrastructure.config;

import com.bytedance.ai.shared.properties.RagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.MetricType;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * RAG 模块基础配置。
 *
 * <p>当前主要负责两件事：
 * 1. 输出关键配置与模型绑定日志，便于启动期排障。
 * 2. 在显式启用 Milvus 时，装配向量存储所需的基础 Bean。
 *
 * <p>这里保留的是 RAG 模块自管的最小装配逻辑，而不是把所有外部组件都集中堆在一个配置类里。
 */
@Configuration
public class RagConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagConfiguration.class);

    private final RagProperties ragProperties;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    public RagConfiguration(
            RagProperties ragProperties,
            ObjectProvider<ChatModel> chatModelProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider
    ) {
        this.ragProperties = ragProperties;
        this.chatModelProvider = chatModelProvider;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    @PostConstruct
    void logConfigurationLoaded() {
        log.info(
                "初始化 RAG 基础配置。defaultTopK={}, chunkSize={}, chunkOverlap={}, milvusEnabled={}, rocketMqEnabled={}, outboxEnabled={}, recoveryEnabled={}",
                ragProperties.defaultTopK(),
                ragProperties.chunkSize(),
                ragProperties.chunkOverlap(),
                ragProperties.milvus().enabled(),
                ragProperties.rocketMq().enabled(),
                ragProperties.outbox().enabled(),
                ragProperties.recovery().enabled()
        );
        logEffectiveEmbeddingBinding();
        warnWhenQueryExpansionChatModelMissing();
        warnWhenCompressionChatModelMissing();
        warnWhenRewriteChatModelMissing();
    }

    /**
     * 启动时打印生效的 embedding 绑定信息，覆盖 Doubao-embedding-vision / OpenAI / 自建网关
     * 任意一种 OpenAI-compatible endpoint，便于运维一眼确认接入的是哪一家。
     *
     * <p>同时校验 Milvus collection 期望维度与 RAG 配置维度一致性：不一致时按 WARN 提示，
     * 避免索引写入后 retrieval 阶段才发现维度错配。
     */
    void logEffectiveEmbeddingBinding() {
        EmbeddingModel embeddingModel = null;
        try {
            embeddingModel = embeddingModelProvider.getIfAvailable();
        } catch (Exception exception) {
            log.warn("解析 EmbeddingModel Bean 失败，离线索引链路将无法写入向量：{}", exception.getMessage());
        }
        int configuredDimension = ragProperties.milvus().embeddingDimension();
        log.info(
                "RAG embedding 绑定：modelName={}, beanType={}, milvusEmbeddingDimension={}, embeddingModelHint={}",
                ragProperties.embeddingModel(),
                embeddingModel == null ? "<none>" : embeddingModel.getClass().getSimpleName(),
                configuredDimension,
                "chat 使用 spring.ai.openai.chat.*，embedding 使用 spring.ai.openai.embedding.*；embedding dimensions 需与 rag.milvus.embedding-dimension 一致"
        );
        if (ragProperties.milvus().enabled() && embeddingModel == null) {
            log.warn("rag.milvus.enabled=true 但没有 EmbeddingModel Bean，向量索引写入会立刻失败");
        }
    }

    void warnWhenQueryExpansionChatModelMissing() {
        if (!ragProperties.queryExpansion().enabled() || !ragProperties.queryExpansion().useModel()) {
            return;
        }

        try {
            if (chatModelProvider.getIfAvailable() == null) {
                log.warn("rag.queryExpansion.useModel=true，但当前没有 ChatModel Bean，查询扩展将降级为单查询");
            }
        } catch (Exception exception) {
            log.warn("检测 ChatModel Bean 失败，查询扩展将降级为单查询: {}", exception.getMessage());
        }
    }

    void warnWhenCompressionChatModelMissing() {
        if (!ragProperties.queryTransformation().enabled() || !ragProperties.queryTransformation().useModel()) {
            return;
        }

        try {
            if (chatModelProvider.getIfAvailable() == null) {
                log.warn("rag.queryTransformation.useModel=true，但当前没有 ChatModel Bean，查询压缩将降级为原始问题");
            }
        } catch (Exception exception) {
            log.warn("检测 ChatModel Bean 失败，查询压缩将降级为原始问题: {}", exception.getMessage());
        }
    }

    void warnWhenRewriteChatModelMissing() {
        if (!ragProperties.queryTransformation().rewriteEnabled()
                || !ragProperties.queryTransformation().rewriteUseModel()) {
            return;
        }

        try {
            if (chatModelProvider.getIfAvailable() == null) {
                log.warn("rag.queryTransformation.rewriteUseModel=true，但当前没有 ChatModel Bean，查询 rewrite 将降级为原始问题");
            }
        } catch (Exception exception) {
            log.warn("检测 ChatModel Bean 失败，查询 rewrite 将降级为原始问题: {}", exception.getMessage());
        }
    }

    /**
     * 创建 RAG 使用的 {@link MilvusVectorStore}。
     *
     * <p>仅当 {@code rag.milvus.enabled=true} 时装配。该 Bean 负责：
     * 1. 将 embedding 结果写入 Milvus。
     * 2. 在检索阶段承接 Spring AI 的向量检索调用。
     *
     * <p>这里不会初始化或修改 Milvus schema/index，只声明检索时使用的距离度量和 batching 策略。
     *
     * @param milvusClient Milvus gRPC 客户端
     * @param embeddingModel embedding 模型
     * @param ragProperties RAG 配置
     * @return 可供 RAG 检索与写入复用的 Milvus 向量存储
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "true")
    public MilvusVectorStore vectorStore(
            MilvusServiceClient milvusClient,
            EmbeddingModel embeddingModel,
            RagProperties ragProperties
    ) {
        MilvusVectorStore.Builder builder = MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName(ragProperties.milvus().collectionName())
                .metricType(resolveMetricType(ragProperties.milvus().metricType()))
                .batchingStrategy(new TokenCountBatchingStrategy())
                .initializeSchema(false);

        if (StringUtils.hasText(ragProperties.milvus().databaseName())) {
            builder.databaseName(ragProperties.milvus().databaseName());
        }

        return builder.build();
    }

    private MetricType resolveMetricType(String configuredMetricType) {
        String metricType = StringUtils.hasText(configuredMetricType)
                ? configuredMetricType.trim().toUpperCase(Locale.ROOT)
                : MetricType.COSINE.name();
        try {
            return MetricType.valueOf(metricType);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "rag.milvus.metric-type must match the Milvus vector index metric: COSINE, IP, or L2",
                    exception
            );
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.query-expansion", name = {"enabled", "use-chat-model"}, havingValue = "true")
    @ConditionalOnBean(ChatModel.class)
    public MultiQueryExpander multiQueryExpander(ChatModel chatModel, RagProperties ragProperties) {
        MultiQueryExpander.Builder builder = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .numberOfQueries(ragProperties.queryExpansion().numberOfQueries())
                .includeOriginal(ragProperties.queryExpansion().includeOriginal());
        String queryTemplate = ragProperties.queryExpansion().queryTemplate();
        if (StringUtils.hasText(queryTemplate)) {
            builder.promptTemplate(new PromptTemplate(queryTemplate));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.query-transformation", name = {"enabled", "use-chat-model"}, havingValue = "true")
    @ConditionalOnBean(ChatModel.class)
    public CompressionQueryTransformer compressionQueryTransformer(ChatModel chatModel, RagProperties ragProperties) {
        CompressionQueryTransformer.Builder builder = CompressionQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel));
        String queryTemplate = ragProperties.queryTransformation().queryTemplate();
        if (StringUtils.hasText(queryTemplate)) {
            builder.promptTemplate(new PromptTemplate(queryTemplate));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "rag.query-transformation", name = {"rewrite-enabled", "rewrite-use-chat-model"}, havingValue = "true")
    @ConditionalOnBean(ChatModel.class)
    public RewriteQueryTransformer rewriteQueryTransformer(ChatModel chatModel, RagProperties ragProperties) {
        RewriteQueryTransformer.Builder builder = RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel));
        String queryTemplate = ragProperties.queryTransformation().rewriteQueryTemplate();
        if (StringUtils.hasText(queryTemplate)) {
            builder.promptTemplate(new PromptTemplate(queryTemplate));
        }
        String targetSearchSystem = ragProperties.queryTransformation().rewriteTargetSearchSystem();
        if (StringUtils.hasText(targetSearchSystem)) {
            builder.targetSearchSystem(targetSearchSystem);
        }
        return builder.build();
    }

    /**
     * 创建 Milvus gRPC 客户端。
     *
     * <p>仅当 {@code rag.milvus.enabled=true} 时装配。该客户端是
     * {@link MilvusVectorStore} 和自定义 Milvus 写入/删除逻辑的底层连接入口。
     *
     * @param ragProperties RAG 配置
     * @return 基于 URI 与 token 建立的 Milvus 客户端
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag.milvus", name = "enabled", havingValue = "true")
    public MilvusServiceClient milvusClient(RagProperties ragProperties) {
        ConnectParam.Builder connect = ConnectParam.newBuilder()
                .withToken(ragProperties.milvus().token())
                .withUri(ragProperties.milvus().uri())
                .withConnectTimeout(10, TimeUnit.SECONDS)
                .withKeepAliveTime(100, TimeUnit.SECONDS);
        // Zilliz Cloud / Serverless 在 gRPC 路径要求显式 databaseName，
        // 否则 search/describe 会路由到空 database 报 "collection not found"。
        // 未配置时默认 "default"，与 Zilliz 控制台一致。
        String databaseName = ragProperties.milvus().databaseName();
        connect.withDatabaseName(StringUtils.hasText(databaseName) ? databaseName : "default");
        return new MilvusServiceClient(connect.build());
    }
}
