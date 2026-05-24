package com.bytedance.ai.shared.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * RAG 模块的统一配置项。
 *
 * @param defaultTopK 默认检索 topK
 * @param chunkSize 文本切片目标大小
 * @param chunkOverlap 相邻切片重叠字符数
 * @param embeddingModel 默认 embedding 模型名
 * @param rocketMq RocketMQ 相关配置
 * @param milvus Milvus 相关配置
 * @param indexing 离线索引流程配置
 * @param queryTransformation 查询改写/压缩配置
 * @param queryExpansion 查询扩展配置
 * @param retrieval 检索权重与候选集配置
 * @param outbox Outbox 投递配置
 * @param recovery 定时补偿配置
 */
@Validated
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        @DefaultValue("3")
        @Min(1)
        @Max(10)
        int defaultTopK,

        @DefaultValue("400")
        @Min(100)
        @Max(4000)
        int chunkSize,

        @DefaultValue("80")
        @Min(0)
        @Max(1000)
        int chunkOverlap,

        @DefaultValue("text-embedding-3-small")
        String embeddingModel,

        @DefaultValue
        RocketMq rocketMq,

        @DefaultValue
        Milvus milvus,

        @DefaultValue
        Indexing indexing,

        @DefaultValue
        QueryTransformation queryTransformation,

        @DefaultValue
        QueryExpansion queryExpansion,

        @DefaultValue
        Retrieval retrieval,

        @DefaultValue
        Outbox outbox,

        @DefaultValue
        Recovery recovery,

        @DefaultValue
        Catalog catalog
) {
    public static RagProperties defaults() {
        return new RagProperties(
                3,
                400,
                80,
                "text-embedding-3-small",
                RocketMq.defaults(),
                Milvus.defaults(),
                Indexing.defaults(),
                QueryTransformation.defaults(),
                QueryExpansion.defaults(),
                Retrieval.defaults(),
                Outbox.defaults(),
                Recovery.defaults(),
                Catalog.defaults()
        );
    }

    /**
     * Catalog 模块配置。
     *
     * <p>抽属性链路改造为 RocketMQ Outbox 模式后，本配置包含三类字段：
     * <ul>
     *   <li>LLM 行为：{@code attributeExtractionTimeoutMillis / Temperature / SystemPrompt}</li>
     *   <li>RocketMQ 投递：{@code rocketMqTopic / rocketMqTag / consumerGroup}</li>
     *   <li>Outbox 调度：{@code dispatchFixedDelayMillis / outboxBatchSize / failureBackoffMillis / sendingStaleMillis}</li>
     * </ul>
     *
     * @param enabled                          是否启用 catalog 属性抽取（false 时 listener 立即 ack 不调 LLM）
     * @param attributeExtractionTimeoutMillis 单次属性抽取超时，单位毫秒
     * @param attributeExtractionTemperature   属性抽取使用的模型 temperature
     * @param attributeExtractionSystemPrompt  属性抽取 system prompt
     * @param rocketMqTopic                    catalog 抽属性 topic
     * @param rocketMqTag                      RocketMQ tag
     * @param consumerGroup                    RocketMQ 消费组
     * @param dispatchFixedDelayMillis         Outbox dispatcher 扫表固定间隔，单位毫秒
     * @param outboxBatchSize                  Outbox dispatcher 单次扫描批量
     * @param failureBackoffMillis             投递失败后的退避时间，单位毫秒
     * @param sendingStaleMillis               SENDING 状态判定为卡住的阈值，单位毫秒
     */
    public record Catalog(
            @DefaultValue("true")
            boolean enabled,

            @DefaultValue("8000")
            @Min(0)
            @Max(120_000)
            long attributeExtractionTimeoutMillis,

            @DefaultValue("0.2")
            double attributeExtractionTemperature,

            @DefaultValue("""
                    你是电商商品理解助手。读完商品描述后，仅输出一个 JSON 对象，键名固定为：
                    {"tags": [], "usage_scenes": [], "target_audience": [], "ingredients": [], "features": []}
                    - tags：核心标签词
                    - usage_scenes：适用场景
                    - target_audience：目标人群
                    - ingredients：成分 / 材质（若不涉及可为空数组）
                    - features：差异化卖点
                    严禁输出多余的解释、Markdown 代码块或前后缀，只输出 JSON。
                    """)
            String attributeExtractionSystemPrompt,

            @DefaultValue("catalog-attribute-extract")
            String rocketMqTopic,

            @DefaultValue("attribute-extract")
            String rocketMqTag,

            @DefaultValue("catalog-attribute-consumer")
            String consumerGroup,

            @DefaultValue("1000")
            @Min(100)
            @Max(60_000)
            long dispatchFixedDelayMillis,

            @DefaultValue("20")
            @Min(1)
            @Max(500)
            int outboxBatchSize,

            @DefaultValue("5000")
            @Min(0)
            @Max(3_600_000)
            long failureBackoffMillis,

            @DefaultValue("60000")
            @Min(1000)
            @Max(3_600_000)
            long sendingStaleMillis
    ) {
        public static Catalog defaults() {
            return new Catalog(
                    true,
                    8_000L,
                    0.2d,
                    "",
                    "catalog-attribute-extract",
                    "attribute-extract",
                    "catalog-attribute-consumer",
                    1_000L,
                    20,
                    5_000L,
                    60_000L
            );
        }
    }

    /**
     * RocketMQ 集成配置。
     *
     * @param enabled 是否启用 RocketMQ 链路
     * @param endpoints RocketMQ endpoints
     * @param topic 索引消息主题
     * @param tag 消息 tag
     * @param consumerGroup 消费组名称
     * @param requestTimeoutSeconds 请求超时时间，单位秒
     * @param sslEnabled 是否启用 SSL
     * @param parseFailureAlertThreshold 消息解析失败达到该次数后触发显式告警并停止继续由应用侧重试
     * @param parseFailurePayloadPreviewLength 消息解析失败时保留的 payload 预览最大长度
     */
    public record RocketMq(
            @DefaultValue("false")
            boolean enabled,
            String endpoints,
            String topic,
            @DefaultValue("rag-index")
            String tag,
            @DefaultValue("rag-index-consumer")
            String consumerGroup,
            @DefaultValue("3")
            int requestTimeoutSeconds,
            @DefaultValue("true")
            boolean sslEnabled,
            @DefaultValue("3")
            int parseFailureAlertThreshold,
            @DefaultValue("1000")
            int parseFailurePayloadPreviewLength,
            @DefaultValue("3")
            int maxAttemptTimes
    ) {
        public static RocketMq defaults() {
            return new RocketMq(false, null, null, "dev-rag-index", "dev-rag-index-consumer", 3, true, 3, 1000,3);
        }
    }

    /**
     * Milvus 向量存储配置。
     *
     * @param enabled 是否启用 Milvus 检索与写入
     * @param uri Milvus 连接 URI
     * @param token Milvus 认证 token
     * @param databaseName 目标数据库名
     * @param collectionName 目标集合名
     * @param embeddingDimension 向量维度
     * @param metricType 目标集合向量索引使用的距离度量；检索请求必须与 Milvus index 保持一致
     * @param similarityThreshold 相似度过滤阈值
     */
    public record Milvus(
            @DefaultValue("false")
            boolean enabled,
            String uri,
            String token,
            String databaseName,
            String collectionName,
            @DefaultValue("1536")
            int embeddingDimension,
            @DefaultValue("COSINE")
            String metricType,
            @DefaultValue("0.2")
            double similarityThreshold
    ) {
        public static Milvus defaults() {
            return new Milvus(false, null, null, null, null, 1536, "COSINE", 0.2d);
        }
    }

    /**
     * 离线索引执行配置。
     *
     * @param maxRetries 单次消息允许的最大重试次数
     * @param retryBackoffMillis 应用内重试退避时间，单位毫秒
     * @param embeddingCacheEnabled 是否启用 embedding 缓存
     */
    public record Indexing(
            @DefaultValue("3")
            @Min(0)
            @Max(10)
            int maxRetries,

            @DefaultValue("1000")
            @Min(0)
            @Max(60_000)
            long retryBackoffMillis,

            @DefaultValue("true")
            boolean embeddingCacheEnabled
    ) {
        public static Indexing defaults() {
            return new Indexing(3, 1_000L, true);
        }
    }

    /**
     * 查询压缩/改写配置。
     *
     * @param enabled 是否启用 CompressionQueryTransformer
     * @param useModel 是否调用模型执行 compression
     * @param minConversationTurns 对话总轮数达到该值后才启动 compression，当前问题计入轮数
     * @param queryTemplate 自定义 compression 提示词模板，需包含 {history} 和 {query} 占位符
     * @param minQuestionLength 当前问题长度达到该值后才触发 compression；小于等于 0 表示不启用该阈值
     * @param minQuestionTokens 当前问题估算 token 数达到该值后才触发 compression；小于等于 0 表示不启用该阈值
     * @param minHistoryTurns 历史 user 轮次达到该值后才触发 compression；小于等于 0 表示不启用该阈值
     * @param timeoutMillis query transform 阶段的超时时间，单位毫秒；小于等于 0 表示不启用超时
     * @param rewriteEnabled 是否启用 RewriteQueryTransformer
     * @param rewriteUseModel 是否调用模型执行 rewrite
     * @param rewriteQueryTemplate 自定义 rewrite 提示词模板
     * @param rewriteTargetSearchSystem Spring AI RewriteQueryTransformer 的 target search system 配置
     */
    public record QueryTransformation(
            @DefaultValue("true")
            boolean enabled,

            @DefaultValue("true")
            boolean useModel,

            @DefaultValue("3")
            @Min(1)
            @Max(32)
            int minConversationTurns,

            @DefaultValue("")
            String queryTemplate,

            @DefaultValue("0")
            @Min(0)
            @Max(8_192)
            int minQuestionLength,

            @DefaultValue("0")
            @Min(0)
            @Max(8_192)
            int minQuestionTokens,

            @DefaultValue("0")
            @Min(0)
            @Max(64)
            int minHistoryTurns,

            @DefaultValue("2000")
            @Min(0)
            @Max(120_000)
            long timeoutMillis,

            @DefaultValue("true")
            boolean rewriteEnabled,

            @DefaultValue("true")
            boolean rewriteUseModel,

            @DefaultValue("")
            String rewriteQueryTemplate,

            @DefaultValue("")
            String rewriteTargetSearchSystem
    ) {
        public static QueryTransformation defaults() {
            return new QueryTransformation(true, true, 3, "", 0, 0, 0, 2_000L, true, true, "", "");
        }
    }

    /**
     * 查询扩展配置。
     *
     * @param enabled 是否启用查询扩展
     * @param useModel 是否调用模型生成扩展 query
     * @param includeOriginal 是否保留原始问题
     * @param numberOfQueries 期望生成的查询数量
     * @param queryTemplate 自定义 query expansion 提示词模板，需包含 {number} 和 {query} 占位符
     * @param timeoutMillis query expand 阶段的超时时间，单位毫秒；小于等于 0 表示不启用超时
     */
    public record QueryExpansion(
            @DefaultValue("true")
            boolean enabled,

            @DefaultValue("true")
            boolean useModel,

            @DefaultValue("true")
            boolean includeOriginal,

            @DefaultValue("3")
            @Min(1)
            @Max(16)
            int numberOfQueries,

            @DefaultValue(""" 
                    """)
            String queryTemplate,

            @DefaultValue("2000")
            @Min(0)
            @Max(120_000)
            long timeoutMillis
    ) {
        public static QueryExpansion defaults() {
            return new QueryExpansion(true, true, true, 3, "", 2_000L);
        }
    }

    /**
     * 检索阶段的权重与候选规模配置。
     *
     * @param keywordHeadingWeight 标题命中的关键词权重
     * @param keywordTitleWeight 文档标题命中的关键词权重
     * @param keywordContentWeight 正文命中的关键词权重
     * @param semanticHeadingWeight 语义检索时标题命中的附加权重
     * @param keywordCandidateMultiplier 关键词检索候选倍数
     * @param keywordCandidateTopKMax 关键词检索候选上限
     * @param hybridPerRetrieverMultiplier 混合检索每路候选倍数
     * @param hybridPerRetrieverTopKMax 混合检索每路候选上限
     * @param multiQueryPerQueryMultiplier 多查询模式下每个 query 的候选倍数
     * @param multiQueryPerQueryTopKMax 多查询模式下每个 query 的候选上限
     * @param semanticCandidateMultiplier 语义检索候选倍数
     * @param semanticFilteredCandidateMultiplier 带过滤条件时的语义候选倍数
     * @param semanticCandidateTopKMax 语义检索候选上限
     * @param adaptiveSingleQueryJoinCandidateMultiplier 单 query 时 join 候选总预算倍数
     * @param adaptiveMultiQueryJoinCandidateMultiplier 多 query 时 join 候选总预算倍数
     * @param maxTotalJoinCandidateBudget 初次检索 join 候选总预算上限
     * @param progressiveWideningEnabled 是否在召回不足时启用二次放大
     * @param progressiveMaxTotalJoinCandidateBudget 二次放大 join 候选总预算上限
     * @param rrfK RRF 融合参数 K
     * @param neighborWindowBefore 返回上下文时向前扩展的切片数
     * @param neighborWindowAfter 返回上下文时向后扩展的切片数
     * @param semanticTimeoutMillis 语义检索超时，单位毫秒；小于等于 0 表示不启用超时
     * @param keywordTimeoutMillis 关键词检索超时，单位毫秒；小于等于 0 表示不启用超时
     * @param queryTimeoutMillis 单个扩展 query 的总检索超时，单位毫秒；小于等于 0 表示不启用超时
     * @param queryConcurrency 多 query 检索并发度
     * @param answerGenerationTimeoutMillis 答案生成超时，单位毫秒；小于等于 0 表示不启用超时
     */
    public record Retrieval(
            @DefaultValue("4.0")
            double keywordHeadingWeight,

            @DefaultValue("1.5")
            double keywordTitleWeight,

            @DefaultValue("1.0")
            double keywordContentWeight,

            @DefaultValue("0.25")
            double semanticHeadingWeight,

            @DefaultValue("8")
            int keywordCandidateMultiplier,

            @DefaultValue("100")
            int keywordCandidateTopKMax,

            @DefaultValue("2")
            int hybridPerRetrieverMultiplier,

            @DefaultValue("20")
            int hybridPerRetrieverTopKMax,

            @DefaultValue("2")
            int multiQueryPerQueryMultiplier,

            @DefaultValue("10")
            int multiQueryPerQueryTopKMax,

            @DefaultValue("3")
            int semanticCandidateMultiplier,

            @DefaultValue("5")
            int semanticFilteredCandidateMultiplier,

            @DefaultValue("50")
            int semanticCandidateTopKMax,

            @DefaultValue("2")
            @Min(1)
            @Max(16)
            int adaptiveSingleQueryJoinCandidateMultiplier,

            @DefaultValue("4")
            @Min(1)
            @Max(16)
            int adaptiveMultiQueryJoinCandidateMultiplier,

            @DefaultValue("24")
            @Min(1)
            @Max(512)
            int maxTotalJoinCandidateBudget,

            @DefaultValue("true")
            boolean progressiveWideningEnabled,

            @DefaultValue("48")
            @Min(1)
            @Max(1024)
            int progressiveMaxTotalJoinCandidateBudget,

            @DefaultValue("60.0")
            double rrfK,

            @DefaultValue("1")
            int neighborWindowBefore,

            @DefaultValue("1")
            int neighborWindowAfter,

            @DefaultValue("1500")
            @Min(0)
            @Max(120_000)
            long semanticTimeoutMillis,

            @DefaultValue("1500")
            @Min(0)
            @Max(120_000)
            long keywordTimeoutMillis,

            @DefaultValue("3000")
            @Min(0)
            @Max(120_000)
            long queryTimeoutMillis,

            @DefaultValue("4")
            @Min(1)
            @Max(16)
            int queryConcurrency,

            @DefaultValue("12000")
            @Min(0)
            @Max(300_000)
            long answerGenerationTimeoutMillis
    ) {
        public static Retrieval defaults() {
            return new Retrieval(4.0d, 1.5d, 1.0d, 0.25d, 8, 100, 2, 20, 2, 10, 3, 5, 50, 2, 4, 24, true, 48, 60.0d, 1, 1, 1_500L, 1_500L, 3_000L, 4, 12_000L);
        }
    }

    /**
     * Outbox 分发表配置。
     *
     * @param enabled 是否启用 Outbox
     * @param dispatchFixedDelayMillis 调度分发固定间隔，单位毫秒
     * @param batchSize 单次分发批量大小
     * @param retryBackoffMillis 投递失败后的退避时间，单位毫秒
     * @param sendingStaleMillis SENDING 状态判定为超时的阈值，单位毫秒
     */
    public record Outbox(
            @DefaultValue("false")
            boolean enabled,

            @DefaultValue("1000")
            @Min(100)
            @Max(60_000)
            long dispatchFixedDelayMillis,

            @DefaultValue("20")
            @Min(1)
            @Max(500)
            int batchSize,

            @DefaultValue("5000")
            @Min(0)
            @Max(3_600_000)
            long retryBackoffMillis,

            @DefaultValue("60000")
            @Min(1000)
            @Max(3_600_000)
            long sendingStaleMillis
    ) {
        public static Outbox defaults() {
            return new Outbox(false, 1_000L, 20, 5_000L, 60_000L);
        }
    }

    /**
     * 定时补偿任务配置。
     *
     * @param enabled 是否启用补偿任务
     * @param fixedDelayMillis 调度固定间隔，单位毫秒
     * @param pendingStaleMillis PENDING 状态超时阈值，单位毫秒
     * @param processingStaleMillis PROCESSING 状态超时阈值，单位毫秒
     * @param failedRetryMillis FAILED 状态再次尝试的冷却时间，单位毫秒
     * @param deletingStaleMillis DELETING 状态进入物理删除补偿的等待时间，单位毫秒
     * @param batchSize 单次补偿批量大小
     */
    public record Recovery(
            @DefaultValue("false")
            boolean enabled,

            @DefaultValue("60000")
            @Min(1000)
            @Max(3_600_000)
            long fixedDelayMillis,

            @DefaultValue("300000")
            @Min(1000)
            @Max(86_400_000)
            long pendingStaleMillis,

            @DefaultValue("600000")
            @Min(1000)
            @Max(86_400_000)
            long processingStaleMillis,

            @DefaultValue("3600000")
            @Min(1000)
            @Max(86_400_000)
            long failedRetryMillis,

            @DefaultValue("60000")
            @Min(1000)
            @Max(86_400_000)
            long deletingStaleMillis,

            @DefaultValue("20")
            @Min(1)
            @Max(500)
            int batchSize
    ) {
        public static Recovery defaults() {
            return new Recovery(true, 60_000L, 300_000L, 600_000L, 3_600_000L, 60_000L, 20);
        }
    }
}
