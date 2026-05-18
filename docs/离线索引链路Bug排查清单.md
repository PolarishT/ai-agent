# 离线索引链路 Bug 排查清单

本文用于排查当前 RAG 项目离线索引链路中的高概率缺陷，重点覆盖：

```text
document -> indexing -> outbox/MQ -> chunk/embedding -> Milvus -> recovery/status
```

优先目标不是先做重构，而是定位重复消费、状态不一致、失败补偿、资源放大、外部依赖异常等问题。

## 核心排查方向

### 1. 入口与触发链路

- 检查文档创建、更新后是否一定触发索引请求。
- 检查删除或更新文档时，旧 chunk、旧 outbox、旧索引任务是否被清理。
- 重点关注 `DocumentCommandService`、`IndexingDocumentEventListener`、`IndexingCommandService`。

### 2. 幂等与重复消费

- 同一个 `documentId + contentSha256` 被重复提交时，是否会重复切片、重复调用 embedding、重复写 Milvus。
- MQ 重投、定时恢复任务、手动重试是否可能同时处理同一文档。
- 在 `RagIndexMessageListener` 进入 `indexDocument(...)` 前，检查是否缺少消费前的原子抢占或快速短路。

### 3. 状态机与数据库状态一致性

- `rag_index_jobs` 的状态是否可能卡在 `PROCESSING`、`RETRYING`、`FAILED`。
- workflow transition 和实际 chunk/Milvus 写入是否可能一边成功一边失败。
- 失败路径是否正确记录 stage、error、retryable、attempt count。

### 4. Outbox 与 MQ 可靠性

- outbox 从 `PENDING -> SENDING -> SENT/FAILED` 是否有竞态。
- 发布 MQ 成功但本地 `markSent` 失败时，是否会造成重复消息。
- `SENDING` 卡死后恢复逻辑是否能正确 reset。
- 无法反序列化的消息是否被审计，是否避免无限重试。

### 5. 切片与 embedding

- 空文档、超大文档、只有标题/表格/代码块的 Markdown 是否能稳定切片。
- chunk index、chunk hash、contentSha256、indexGeneration 是否稳定。
- embedding cache 命中/未命中逻辑是否会因 hash 不一致失效。
- embedding 失败时是否会进入可恢复失败，而不是部分写入后状态成功。

### 6. PostgreSQL 持久化

- chunk 批量插入是否有事务边界问题。
- 同一文档新版本索引成功后，旧版本 chunk 是否仍可能被检索到。
- schema、repository SQL、唯一约束是否支持幂等和并发。
- 重点关注 `JdbcRagChunkRepository`、`JdbcRagIndexJobRepository`、`JdbcRagIndexOutboxRepository`。

### 7. Milvus 写入与删除

- Milvus disabled 时，链路是否能正常只走 keyword retrieval。
- Milvus upsert 失败时，PostgreSQL chunk 是否已经写入，状态如何回滚或标记。
- 删除向量时 collection 名、空 token、空 URI、空返回值是否处理正确。
- 重点关注 `RagMilvusVectorIndexer` 中删除、upsert、异常处理相关逻辑。

### 8. 恢复任务与最终失败

- recovery 是否只捞真正 stale 的任务，避免抢正在执行的任务。
- retry 上限、backoff、最终失败状态是否符合预期。
- 最终失败通知是否可能在大量失败时造成通知风暴。

### 9. 资源与性能

- 大文档是否会一次性持有原文、blocks、chunks、drafts、embedding map、Milvus payload。
- chunk 数极大时是否导致 SQL 太大、JVM 内存峰值过高、模型调用成本失控。
- 对照 `docs/内存优化方案.md` 中的大文档、batch、embedding cache、Milvus 写入建议逐项检查。

### 10. 观测与可排障性

- 每次索引是否有稳定 correlation id，例如 `documentId + contentSha256`。
- 关键阶段是否有 metrics：enqueue、dispatch、consume、chunk、embedding、milvus、success/failure。
- timeline API 是否能还原一次失败的完整过程。

## 建议排查顺序

1. 先跑现有 indexing 相关测试，确认当前基线。
2. 从幂等问题入手：重复请求、MQ 重投、recovery 并发，这是最容易放大成本的问题。
3. 再查状态一致性：任务状态、outbox 状态、chunk 写入、Milvus 写入是否可能分叉。
4. 然后查异常路径：embedding 失败、Milvus 失败、消息解析失败、数据库写入失败。
5. 最后做大文档压测和资源检查，验证内存、chunk 数、SQL 大小、重试风暴。

## 测试场景清单

- 单文档正常索引：创建文档后状态最终成功，chunk 可检索。
- 重复索引请求：同一 `documentId + contentSha256` 多次触发，不重复 embedding。
- 文档更新：新版本成功后，只检索到新版本 chunk。
- MQ 重投：同一消息消费两次，最终只有一个有效索引结果。
- recovery 撞 MQ：恢复任务和 MQ 消费同时触发，不产生双写。
- embedding 失败：任务进入可重试失败，错误 stage 正确。
- Milvus 失败：状态不误报成功，PostgreSQL 与向量库一致性可解释。
- 超大文档：达到限制时明确失败，不拖垮 JVM。
- 消息反序列化失败：写入 failure audit，不进入无限失败循环。
- outbox `SENDING` 卡死：超过 stale 时间后可恢复重试。

## 默认假设

- “离线代码侧”指当前仓库的离线 RAG 索引链路，而不是在线问答链路。
- 优先排查 bug 风险，不先做重构。
- 当前最值得优先看的模块是 `rag/document`、`rag/indexing`、`rag/infrastructure/mq` 和相关 JDBC repository。
- 本文档不包含真实密钥、生产环境连接串或部署敏感信息。
