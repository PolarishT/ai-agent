1. 整个项目还没做并发控制
2. 大模型被历史记录污染的问题典型场景：添加购物车 → 添加失败 → 查询购物车，但 LLM 受历史影响，以为用户还要继续添加购物车。（高优）
3. TODO：OrderCartSnapshotService 改成 CartView -> CartSnapshot DTO -> ObjectMapper.convertValue(...)，不要手写
   Map.put，也不要直接 CartView 转 Map。
4.

## 🟡 可选优化（不影响功能，记一笔以后再说）

| 项                               | 当前                                                                                                                        | 优化方向                                                                                           |
|---------------------------------|---------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| chunk_type 过滤                   | `pqKeywordRetrieve` 构造 `ProductSearchRequest` 时 `includeChunkTypes=List.of()` 不限制                                         | DDL 已有 `rag_chunks.chunk_type` 列 + 索引，可以在 condition 里加 chunkType 偏好（如商品对比偏好 ATTR/SPEC，FAQ 不参与） |
| source_type 过滤                  | retriever WHERE 没有 `c.source_type = 'CATALOG_PRODUCT'` 之类约束                                                               | DDL 新增了 `rag_chunks.source_type` 列；万一未来同库混入其它 source_type，应该加个 WHERE 防御                        |
| queryMode 实际未用                  | `ProductQueryCondition.queryMode` (KEYWORD_ONLY/SEMANTIC_ONLY/HYBRID) LLM 输出后没被 `ProductSearchSpiAdapter` 消费，一直跑 hybrid   | adapter `searchProduct(...)` 可以读 `request` 上的 mode（需要在 `ProductSearchRequest` 加字段）跳过一路         |
| excludeColor 走 LIKE 而不是 gin     | retriever 用 `attributes_json->>'color' LIKE '%黑色%'`，走不上 `idx_catalog_product_attributes_gin (jsonb_path_ops)`             | 改成 `NOT (attributes_json @> '{"color":"黑色"}'::jsonb)` 可吃到 GIN 索引；但语义略严（精确等值不再前缀匹配）             |
| pending 行查询索引                   | `findActiveByUserIdAndConversationId` WHERE 含 `status='ACTIVE' AND expire_at > NOW()`，但只有 `(user_id, conversation_id)` 索引 | 可加部分索引 `WHERE status = 'ACTIVE'`；同会话 ACTIVE 行 ≤ 1，影响很小                                         |
| 新表 knowledge / faq / review 未利用 | DDL 新增了 `catalog_product_knowledge` / `_faq` / `_review`，但 product query 链路还只用 `rag_chunks` 的索引内容                         | 后续可在索引侧把这三张表的文本 chunk 化（带 chunk_type=KNOWLEDGE/FAQ/REVIEW），product_query 自动受益                  |



6. ；离线链路对spring ai 异常错误没有做全局拦截

com.bytedance.ai.indexing.model.RagIndexAttemptException: 索引失败 [unknown]: 404 -
at com.bytedance.ai.indexing.application.RagIndexingService.indexDocument(RagIndexingService.java:202)
at com.bytedance.ai.indexing.messaging.RagIndexMessageListener.consume(RagIndexMessageListener.java:117)
at io.opentelemetry.javaagent.instrumentation.rocketmqclient.v5_0.MessageListenerWrapper.consume(MessageListenerWrapper.java:44)
at org.apache.rocketmq.client.java.impl.consumer.ConsumeTask.call(ConsumeTask.java:64)
at org.apache.rocketmq.client.java.impl.consumer.ConsumeTask.call(ConsumeTask.java:36)
at org.apache.rocketmq.shaded.com.google.common.util.concurrent.TrustedListenableFutureTask$TrustedFutureInterruptibleTask.runInterruptibly(TrustedListenableFutureTask.java:131)
at org.apache.rocketmq.shaded.com.google.common.util.concurrent.InterruptibleTask.run(InterruptibleTask.java:74)
at org.apache.rocketmq.shaded.com.google.common.util.concurrent.TrustedListenableFutureTask.run(TrustedListenableFutureTask.java:82)
at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1090)
at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:614)
at java.base/java.lang.Thread.run(Thread.java:1474)
Caused by: org.springframework.ai.retry.NonTransientAiException: 404 -
at org.springframework.ai.retry.RetryUtils$1.handleError(RetryUtils.java:90)
at org.springframework.ai.retry.RetryUtils$1.handleError(RetryUtils.java:75)
at org.springframework.web.client.StatusHandler.lambda$fromErrorHandler$0(StatusHandler.java:98)
at org.springframework.web.client.StatusHandler.handle(StatusHandler.java:75)
at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.applyStatusHandlers(DefaultRestClient.java:943)
at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.lambda$readBody$0(DefaultRestClient.java:932)
at org.springframework.web.client.DefaultRestClient.readWithMessageConverters(DefaultRestClient.java:224)
at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.readBody(DefaultRestClient.java:931)
at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.lambda$toEntityInternal$0(DefaultRestClient.java:871)
at org.springframework.web.client.DefaultRestClient$DefaultRequestBodyUriSpec.exchangeInternal(DefaultRestClient.java:617)
at org.springframework.web.client.DefaultRestClient$DefaultRequestBodyUriSpec.exchange(DefaultRestClient.java:572)
at org.springframework.web.client.RestClient$RequestHeadersSpec.exchange(RestClient.java:747)
at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.executeAndExtract(DefaultRestClient.java:924)
at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.toEntityInternal(DefaultRestClient.java:870)
at org.springframework.web.client.DefaultRestClient$DefaultResponseSpec.toEntity(DefaultRestClient.java:866)
at org.springframework.ai.openai.api.OpenAiApi.embeddings(OpenAiApi.java:348)
at org.springframework.ai.openai.OpenAiEmbeddingModel.lambda$call$2(OpenAiEmbeddingModel.java:175)
at org.springframework.core.retry.RetryTemplate.execute(RetryTemplate.java:174)
at org.springframework.ai.retry.RetryUtils.execute(RetryUtils.java:168)
at org.springframework.ai.openai.OpenAiEmbeddingModel.lambda$call$1(OpenAiEmbeddingModel.java:174)
at io.micrometer.observation.Observation.observe(Observation.java:634)
at org.springframework.ai.openai.OpenAiEmbeddingModel.call(OpenAiEmbeddingModel.java:173)
at org.springframework.ai.embedding.EmbeddingModel.embed(EmbeddingModel.java:85)
at com.bytedance.ai.indexing.service.RagMilvusVectorIndexer.resolveEmbeddings(RagMilvusVectorIndexer.java:226)
at com.bytedance.ai.indexing.service.RagMilvusVectorIndexer.add(RagMilvusVectorIndexer.java:78)
at com.bytedance.ai.indexing.application.RagIndexingService.indexDocumentOnce(RagIndexingService.java:340)
at com.bytedance.ai.indexing.application.RagIndexingService.indexDocument(RagIndexingService.java:120)
... 10 common frames omitted

7. Caused by: org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint "uq_rag_documents_source_uri_source_type"
   Detail: Key (source_uri, source_type)=(product:manual-offline-e2e-001:profile, product_profile) already exists.
   at org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(QueryExecutorImpl.java:2875)
   at org.postgresql.core.v3.QueryExecutorImpl.processResults(QueryExecutorImpl.java:2560)
   at org.postgresql.core.v3.QueryExecutorImpl.execute(QueryExecutorImpl.java:429)
   at org.postgresql.jdbc.PgStatement.executeInternal(PgStatement.java:526)
   at org.postgresql.jdbc.PgStatement.execute(PgStatement.java:436)
   at org.postgresql.jdbc.PgPreparedStatement.executeWithFlags(PgPreparedStatement.java:196)
   at org.postgresql.jdbc.PgPreparedStatement.executeUpdate(PgPreparedStatement.java:157)
   at com.zaxxer.hikari.pool.ProxyPreparedStatement.executeUpdate(ProxyPreparedStatement.java:61)
   at com.zaxxer.hikari.pool.HikariProxyPreparedStatement.executeUpdate(HikariProxyPreparedStatement.java)
   at org.springframework.jdbc.core.JdbcTemplate.lambda$update$1(JdbcTemplate.java:998)
   at org.springframework.jdbc.core.JdbcTemplate.execute(JdbcTemplate.java:670)
   ... 78 common frames omitted
rag 离线链路上传文档，没有全局异常拦截器
8. catalog_product_knowledge 表中的title字段有什么用？是否可以删除？
9. rag_properties 属性抽到env文件里
10. product-query 流程目前的检索链路应该是 在检索前构建slot ，检索前：slot 转成 SQL / DB hard filter，用来缩小 PostgreSQL 关键词检索范围；
同时也转成 Milvus scalar filter metadata，用来缩小向量检索范围。然后同时进行关键词检索和向量检索。然后拿到结果（如果有一个没有结果（超时或者是null） 或者两个都没有结果）都应该build一个结果（兜底），
并且这里日志要输出warning说进行了降级查询，并且告知是哪个进行了降级，然后需要回到数据库拿原文。并基于slot 转成 Java predicate / SQL 条件，对已经回库补全的原文和商品字段做最终硬过滤。⭐，
然后进行打分 融合 排序。构建最终的reponse准备丢给llm 做 generate。最终失败的文章需要重新写（有硬编码，不优雅）
11. 商品离线入库环节，需要根据title使用分词器进行切分，并存入到catalog_product tag字段里，元气森林 白葡萄味 苏打气泡水 eg:["元气森林","白葡萄味","苏打气泡水"],并赋予一个稍微高一点权重，提升 sql 检索阶段的召回成功率。
| 项                                                                                                                                                                                                                                                                                                                                                                 | 当前                            | 建议                                                                                                   |
|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------|------------------------------------------------------------------------------------------------------|
| `pending_cart_actions.status` 无 CHECK 约束                                                                                                                                                                                                                                                                                                                          | DB 任何字符串都能写入                  | 既然 DDL 已冻结，不动；但可观察将来若误写非枚举值不会被拦                                                                      |
| `findActiveByUserIdAndConversationId` 走 `(user_id, conversation_id)` 索引后再过滤 `status='WAITING_USER_SELECTION' AND expire_at > NOW()`                                                                                                                                                                                                                               | 复用现有索引                        | 高并发场景可加 `WHERE status = 'WAITING_USER_SELECTION'` 部分索引；当前同会话 ACTIVE 行 ≤ 1，影响很小                       |
| `ProductCandidate.externalRef` 三处消费（[`DefaultCandidateSelectionLlmService:82`](src/main/java/com/bytedance/ai/graph/cartmanage/subgraph/DefaultCandidateSelectionLlmService.java)、[`CartManageSubgraphFactory.normalizedCandidateText:1190`](src/main/java/com/bytedance/ai/graph/cartmanage/subgraph/CartManageSubgraphFactory.java)、`ProductComparisonBuilder`） | 用作冗余 LLM 提示字段 + match text 补充 | 下个版本删字段时，把这三处的 `candidate.externalRef()` 引用一并去掉，再清理 record 上的 7→6 个字段                                |
| `CartManageSubgraphFactory` 还有些方法仍接收 `Long spuId` 命名（与 `cart_item.spu_id` 列保持一致)                                                                                                                                                                                                                                                                                  | 现状                            | DDL 把列名留作 `spu_id` 没改 → cart bounded context 仍按 spuId 命名，cartmanage 这层保持 String 适配。**不动**（spec 没要求改） |
|                                                                                                                                                                                                                                                                                                                                                                   |                               |                                                                                                      |