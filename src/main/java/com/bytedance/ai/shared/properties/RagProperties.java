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
 * @param outbox Outbox 投递配置
 * @param recovery 定时补偿配置
 * @param productQuery 商品查询 subgraph 配置（仅 product_query_workflow 消费）
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
        Outbox outbox,

        @DefaultValue
        Recovery recovery,

        @DefaultValue
        Catalog catalog,

        @DefaultValue
        ProductQuery productQuery
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
                Outbox.defaults(),
                Recovery.defaults(),
                Catalog.defaults(),
                ProductQuery.defaults()
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
            double similarityThreshold,
            @DefaultValue
            ProductSchema productSchema
    ) {
        public static Milvus defaults() {
            return new Milvus(false, null, null, null, null, 1536, "COSINE", 0.2d, ProductSchema.defaults());
        }

        /**
         * Milvus 集合中 product 相关的 scalar 字段映射。
         *
         * <p>{@link com.bytedance.ai.graph.product.retrieval.filter.MilvusScalarFilterBuilder}
         * 只为「值非空」的字段生成 scalar filter；某字段没在 Milvus collection schema 里时把对应配置留空，
         * builder 会跳过该项，未进入 Milvus filter 的条件由 PostgreSQL hard filter 与 hydrate 后 post-filter 兜底。
         *
         * <p>默认所有字段都为空：与现网 rag_chunks 集合保持兼容（当前只写了
         * vectorId/documentId/productId/indexGeneration/sourceType/chunkType/chunkIndex 七个 metadata key）。
         * 若运维侧补齐字段并重新索引，按需在 {@code application.yml} 里把对应字段名填上即可启用 Milvus 侧推下。
         *
         * @param statusField       商品状态字段名（VARCHAR，如 "ON_SHELF" / "ACTIVE"）；空则跳过
         * @param activeStatusValue {@code statusField != null} 时表示「在售」的具体取值
         * @param stockField        库存字段名（INT64）；空则跳过 stock 过滤
         * @param priceField        价格字段名（DOUBLE）；空则跳过 price 过滤
         * @param categoryIdField   品类 id 字段名（INT64）；与 {@code categoryNameField} 二选一，前者优先
         * @param categoryNameField 品类 name 字段名（VARCHAR）；fallback
         * @param brandIdField      品牌 id 字段名（INT64）
         * @param brandNameField    品牌 name 字段名（VARCHAR）
         * @param productIdField    商品 id 字段名（INT64）；不直接拼到 filter，但暴露给 builder 供 productId in [...] 场景
         */
        public record ProductSchema(
                String statusField,
                @DefaultValue("ACTIVE")
                String activeStatusValue,
                String stockField,
                String priceField,
                String categoryIdField,
                String categoryNameField,
                String brandIdField,
                String brandNameField,
                @DefaultValue("productId")
                String productIdField
        ) {
            public static ProductSchema defaults() {
                return new ProductSchema(null, "ACTIVE", null, null, null, null, null, null, "productId");
            }
        }
    }

    /**
     * 离线索引执行配置。
     *
     * @param maxRetries 单次消息允许的最大重试次数
     * @param retryBackoffMillis 应用内重试退避时间，单位毫秒
     */
    public record Indexing(
            @DefaultValue("3")
            @Min(0)
            @Max(10)
            int maxRetries,

            @DefaultValue("1000")
            @Min(0)
            @Max(60_000)
            long retryBackoffMillis
    ) {
        public static Indexing defaults() {
            return new Indexing(3, 1_000L);
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

    /**
     * 商品查询 subgraph（product_query_workflow）配置。
     *
     * <p>product retrieval 与 graph 两侧共用同一份配置：product retrieval 侧负责 SQL 推下、RRF 与基础打分，
     * graph 侧负责 condition-specific 二次排序与多轮上下文持久化。
     *
     * @param defaultTopK            最终返回给用户的商品数量上限
     * @param keywordCandidateTopK   关键词分支召回候选数
     * @param semanticCandidateTopK  语义分支召回候选数
     * @param rrfK                   RRF 融合参数 K
     * @param keywordTimeoutMillis   关键词分支超时，单位毫秒；≤0 表示不启用超时
     * @param semanticTimeoutMillis  语义分支超时，单位毫秒；≤0 表示不启用超时
     * @param pendingTtlHours        商品查询上下文在 agent_context_items 中的存活时间（小时）
     * @param mustHaveStockDefault   未显式表达时是否默认要求有库存
     * @param rankWeights            基础排序权重 + condition 二次排序权重
     * @param comparison             对比节点配置
     */
    public record ProductQuery(
            @DefaultValue("8")
            @Min(1)
            @Max(50)
            int defaultTopK,

            @DefaultValue("20")
            @Min(1)
            @Max(500)
            int keywordCandidateTopK,

            @DefaultValue("20")
            @Min(1)
            @Max(500)
            int semanticCandidateTopK,

            @DefaultValue("60.0")
            double rrfK,

            @DefaultValue("8000")
            @Min(0)
            @Max(120_000)
            long keywordTimeoutMillis,

            @DefaultValue("8000")
            @Min(0)
            @Max(120_000)
            long semanticTimeoutMillis,

            @DefaultValue("24")
            @Min(1)
            @Max(168)
            long pendingTtlHours,

            @DefaultValue("true")
            boolean mustHaveStockDefault,

            @DefaultValue
            RankWeights rankWeights,

            @DefaultValue
            Comparison comparison
    ) {
        public static ProductQuery defaults() {
            return new ProductQuery(
                    8, 40, 40, 60.0d,
                    1_500L, 1_500L,
                    24L, true,
                    RankWeights.defaults(),
                    Comparison.defaults()
            );
        }

        /**
         * 排序权重。基础权重由 graph product 侧 ProductBaseRanker 使用；
         * condition* 权重由 graph 侧 ProductRanker 在二次排序时使用。
         */
        public record RankWeights(
                @DefaultValue("0.30") double keyword,
                @DefaultValue("0.30") double semantic,
                @DefaultValue("0.15") double categoryMatch,
                @DefaultValue("0.10") double includeMatch,
                @DefaultValue("0.10") double priceMatch,
                @DefaultValue("0.05") double stock,
                @DefaultValue("0.10") double conditionIncludeBoost,
                @DefaultValue("0.05") double conditionSortBoost
        ) {
            public static RankWeights defaults() {
                return new RankWeights(0.30d, 0.30d, 0.15d, 0.10d, 0.10d, 0.05d, 0.10d, 0.05d);
            }
        }

        /**
         * 对比节点配置。
         *
         * @param defaultTopN comparisonTargets 为空时默认取前 N 个候选做对比
         */
        public record Comparison(
                @DefaultValue("2")
                @Min(2)
                @Max(5)
                int defaultTopN
        ) {
            public static Comparison defaults() {
                return new Comparison(2);
            }
        }
    }
}
