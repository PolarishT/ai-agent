<p align="right">
  <a href="./README.md">简体中文</a>
</p>

<p align="center">
  <a href="https://involutionhell.com">
    <picture>
      <!-- Dark mode logo -->
      <source media="(prefers-color-scheme: dark)" srcset="https://involutionhell.com/logo/logoInDark.svg">
      <!-- Light mode logo -->
      <source media="(prefers-color-scheme: light)" srcset="https://involutionhell.com/logo/logoInLight.svg">
      <!-- Fallback -->
      <img src="https://involutionhell.com/mascot.webp" width="150" alt="Involution Hell Logo">
    </picture>
  </a>
</p>

<p align="center">
  <img src="https://readme-typing-svg.demolab.com/?font=Noto+Sans+SC&weight=700&size=32&pause=1000&color=f6671b&center=true&vCenter=true&width=420&lines=%E5%86%85%E5%8D%B7%E5%9C%B0%E7%8B%B1%E5%90%8E%E7%AB%AF&duration=3000" alt="Typing SVG" />
</p>

<p align="center">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" />
  <img alt="Java" src="https://img.shields.io/badge/Java-7F52FF?style=for-the-badge&logo=java&logoColor=white" />
  <img alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&logo=postgresql&logoColor=white" />
  <img alt="Neon" src="https://img.shields.io/badge/Neon-00E599?style=for-the-badge&logo=neon&logoColor=black" />
</p>

---

## 📖 项目简介

这是 **[内卷地狱 (Involution Hell)](https://involutionhell.com)** 的一个后端服务仓库。本项目采用 **Java 25** 和 **Spring Boot 4** 构建，当前主要聚焦于 RAG 文档管理、离线索引链路和在线检索问答能力。

### 核心能力
- **Spring Boot 4**：基于最新 LTS 版本的 Web 服务基线。
- **模块化单体**：基于 **Spring Modulith** 对 `document / indexing / retrieval / infrastructure` 做边界治理。
- **持久化层**：当前以 JDBC + PostgreSQL/H2 为主，支撑文档、切片、作业、Outbox 和审计记录。
- **高性能编译**：原生支持 **GraalVM Native Image**，提供极速启动与极低内存占用。

## 技术栈

- **语言 & 框架**: Java 25, Spring Boot 4.0.4
- **模块治理**: Spring Modulith 2.0.5
- **数据库**: PostgreSQL / H2
- **AI 抽象**: Spring AI 2.0.0-M4
- **消息与工作流**: RocketMQ 5.x, Spring Statemachine 4.0.1
- **构建工具**: Maven 3.9+, GraalVM Build Tools

## 目录概览

- [docs/architecture/modulith-architecture.md](/Users/polaris/unsw-opensource/rag/docs/architecture/modulith-architecture.md): 当前 Modulith 架构、模块职责、依赖规则与事件流说明
- `src/main/java/com/involutionhell/backend/common`: 跨模块稳定通用能力
- `src/main/java/com/involutionhell/backend/rag/document`: 文档生命周期与索引请求发布
- `src/main/java/com/involutionhell/backend/rag/indexing`: 索引工作流、Outbox、MQ、Milvus 写入与时间线
- `src/main/java/com/involutionhell/backend/rag/retrieval`: 检索与问答链路
- `src/main/java/com/involutionhell/backend/rag/infrastructure`: 配置装配、调度、运行时提示与技术性壳层
- `src/main/java/com/involutionhell/backend/rag/shared`: 稳定共享属性、值对象与工具

## 模块架构

当前仓库保持单 Maven 模块、单部署单体不变，但内部已按 Spring Modulith 收口为以下模块：

- `common`
- `rag.shared`
- `rag.document`
- `rag.indexing`
- `rag.retrieval`
- `rag.infrastructure`

核心依赖方向：

- `document -> shared`
- `indexing -> document::api + document::spi + shared`
- `retrieval -> indexing::api + shared`
- `infrastructure -> 各模块公开契约`

其中 `document.spi` 是给 `indexing` 使用的内部协作契约，用来替代直接依赖 `document.persistence`。
完整说明见 [docs/architecture/modulith-architecture.md](/Users/polaris/unsw-opensource/rag/docs/architecture/modulith-architecture.md)。


## 快速开始

### 1. 准备环境
确保已安装 JDK 25 以及 Maven。

```bash
./mvnw -v
```

### 2. 配置运行环境
根据所用 profile 提供数据库、模型与可选的 RocketMQ / Milvus 参数。当前仓库没有内置 `docker compose` 或 `.env.example`，建议直接使用 `application-*.properties` 或环境变量覆盖。

如果启用 RocketMQ，建议至少关注这些参数：

- `rag.rocket-mq.enabled`
- `rag.rocket-mq.endpoints`
- `rag.rocket-mq.topic`
- `rag.rocket-mq.consumer-group`
- `rag.rocket-mq.parse-failure-alert-threshold`
- `rag.rocket-mq.parse-failure-payload-preview-length`
- `rag.outbox.enabled`
- `rag.outbox.retry-backoff-millis`
- `rag.outbox.sending-stale-millis`
- `rag.recovery.enabled`

### 3. 启动后端服务
在根目录下直接启动 JVM 模式：

```bash
./mvnw spring-boot:run
```

默认接口入口：`http://127.0.0.1:8080/api/v1`

### 4. 动态调整日志级别
服务通过 Actuator 暴露 `loggers` endpoint，可在不重启服务的情况下临时调整包或类的日志级别。

查看某个包当前日志级别：

```bash
curl http://127.0.0.1:8080/api/v1/actuator/loggers/com.involutionhell.backend.rag
```

临时开启 RAG 模块 `DEBUG` 日志：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/actuator/loggers/com.involutionhell.backend.rag \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"DEBUG"}'
```

排障结束后恢复为 `INFO`：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/actuator/loggers/com.involutionhell.backend.rag \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"INFO"}'
```

生产环境暴露 `loggers` endpoint 前需确保 Actuator 只在受控网络或鉴权网关后访问。

### 5. 调用示例接口
文档创建：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/public/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "sourceType": "markdown",
    "sourceUri": "docs/example.md",
    "externalRef": "example-1",
    "title": "Example Document",
    "content": "# Example\n\nThis is a test document.",
    "metadata": {}
  }'
```

问答示例：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/public/rag/ask \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -N \
  -d '{
    "question": "当前知识库里有哪些文档？",
    "topK": 3,
    "sourceUriPrefix": null,
    "tags": [],
    "headingPathContains": null,
    "history": []
  }'
```

## 测试与构建

### 运行测试
```bash
./mvnw clean test
```

### Native Image 编译
```bash
./mvnw -DskipTests native:compile-no-fork
# 产物位于 target/backend
```

## 离线索引链路说明

当前离线索引链路有两种运行模式：

- 直连模式：`rag.rocket-mq.enabled=false`
- MQ 模式：`rag.rocket-mq.enabled=true`，可选搭配 `rag.outbox.enabled=true`

在 MQ 模式下，离线链路目前包含这些保护：

- 文档入库与消息投递通过 Outbox 解耦
- Outbox 发送失败会回写 workflow `retry`，避免 timeline 只看到 `dispatch` 却不知道真实投递失败
- 如果消息已发出，但 `markSent` 落库失败，事件会暂时保持 `SENDING`，后续由补偿逻辑按 job 侧证据做 reconciliation，而不是立即重复投递
- 无法反序列化的 RocketMQ 消息会写入 `rag_index_message_failures`
- 解析失败在达到 `rag.rocket-mq.parse-failure-alert-threshold` 前会继续返回 MQ `FAILURE`
- 达到阈值后会输出显式高等级告警日志，并停止继续由应用侧重复重试

当前相关审计/观测对象包括：

- `rag_index_jobs`
- `rag_index_job_transitions`
- `rag_index_outbox`
- `rag_index_message_failures`
- `rag.indexing.message.parse_failure.count`

### 当前已识别风险：消费幂等缺口

当前 `RagIndexMessageListener` 在进入 `ragIndexingService.indexDocument(...)` 之前，还没有“消费权原子抢占”这一层前置拦截。  
这意味着在以下场景下，重复消息可能会重复执行昂贵的切片和 embedding：

- RocketMQ 因 ACK 丢失、网络抖动或消费确认失败而重发同一条消息
- 定时补偿任务和 MQ 重投在时间窗口内撞到同一 `documentId + contentSha256`
- 两个消费者几乎同时读取到同一个 `QUEUED / DISPATCHING` 状态，并同时推进到 `START_ATTEMPT`

风险后果：

- 同一文档版本可能被重复调用 embedding 模型
- 最终结果虽然通常会因 stable `vectorId` / generation 收敛，但会重复消耗模型额度和索引时间
- 在 RAG 场景下，这类重复计算会直接转化为成本浪费

当前仅靠“已成功再重投时的状态收敛”是不够的；因为它不能阻止并发中的重复消费。

### 建议修法

建议采用两层幂等保护：

1. 快速短路  
   在消费入口先检查：
   - 当前 `documentId + contentSha256` 是否已经是 `SUCCEEDED`
   - 当前 `messageId` 是否已经被标记为 `CONSUMED`

2. 原子抢占执行权  
   在真正进入 `indexDocument(...)` 前，通过数据库执行一次原子 claim：
   - 仅允许一个消费者把 job 从 `QUEUED / DISPATCHING` 抢占到可执行状态
   - 抢占失败的消费者直接视为重复投递，记录日志后返回 `ConsumeResult.SUCCESS`

只有“快速短路 + 原子抢占”同时存在，才能真正把重复烧钱的问题挡在 embedding 之前。

## RAG 执行计划

> [!NOTE]
> 下面的清单按“先建立稳定的问答执行模型，再做检索性能，再做召回质量”的顺序排列。

### 前置阶段：异步问答与流式返回
- [ ] 将同步阻塞的 `RagService.ask` 升级为“提交问答任务 + 异步消费执行”的架构，避免复杂问题导致 HTTP / Nginx 超时断开。
- [ ] 用户发起提问时，立即返回 `ticketId` 或 `conversationId`，后台通过 RocketMQ 异步执行 `query transform`、`multi-retrieval` 与 `answer generate`。
- [ ] 为问答任务补齐状态机，至少覆盖 `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`，并支持按 `ticketId` 查询状态。
- [ ] 支持流式重连与续传，避免前端断线后整段回答丢失。
- [ ] 为异步问答链路增加超时控制、失败重试、死信处理与幂等保障，避免重复消费或长时间挂起。
- [ ] 评估是否需要持久化部分生成结果与事件日志，便于审计、排障和对话回放。

### 第二阶段：性能与稳定性
- [ ] 增加超时后的降级策略，避免单路阻塞拖垮整条问答链路。

### 第三阶段：PostgreSQL 关键词检索优化
目标：让关键词检索路径更稳定、更可预测，减少复杂 SQL 退化风险。

- [ ] 用 `EXPLAIN ANALYZE` 验证当前 FTS + `pg_trgm` 查询是否稳定命中索引。
- [ ] 将 `title`、`headingPathText` 等检索字段下沉到 `rag_chunks`，减少跨表组合计算。
- [ ] 增加物化 `search_vector` 列，并为其建立单一 GIN 索引。
- [ ] 评估并优化 `LIKE` 兜底分支比例，避免退化为大范围扫描。
- [ ] 修复索引失败通知风暴问题：当大量文档同一时间处理失败时，`SMS` / `Mail` 不应按“单条 index 错误”和“最终入库失败”逐条轰炸收件人；需要引入通知聚合、限流、去重或摘要批次发送机制，并保持通知组装、聚合策略与发送通道解耦。

### 第四阶段：召回质量优化
- [ ] 根据问题类型做检索策略路由，如代码/API/报错类问题优先 `keyword`，解释型问题优先 `hybrid`。
- [ ] 将 `multi-query expansion` 改为自适应策略，而不是固定扩 3 路。
- [ ] 优化标题、Heading、精确 token 命中的 boost 权重。
- [ ] 在规则优化完成后，再评估是否引入轻量级 reranker。

## 贡献规范
- 保持代码可编译，提交前执行 `./mvnw clean test`。
- 业务逻辑请优先放在模块内的 `application / workflow / service` 分层中，保持 `web` 层简洁。
- 对外暴露的方法尽量补齐 Javadoc 注释。

---

## 维护说明
本项目由 **InvolutionHell** 社区维护，旨在打破内卷，共建透明的计算机学习生态。

### 测试代码
```text
curl -X POST "http://localhost:8080/api/v1/public/rag/documents/create" \
  -H "Accept: application/json" \
  -F "file=@/Users/polaris/Downloads/AGENT.md"
```

Multipart 上传时，`sourceType`、`sourceUri`、`externalRef`、`title` 会从 Markdown frontmatter / 正文标题 / 文件名中自动推导，不需要作为表单字段手动上传。
