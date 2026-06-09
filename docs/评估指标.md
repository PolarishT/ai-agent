# Agent 回归评测结果

> 模板：每次跑完 `w2-regression-cases.json` 把结果以「时间倒序」追加到本文件，**不删除**历史，方便 diff 走势。

## 数据集

- 路径：`src/test/resources/eval/w2-regression-cases.json`
- 版本：`w2-regression-2026-05`
- 规模：5 意图 × 10 题 = 50 题
  - `basic_recommend` × 10
  - `filter_by_attr` × 10
  - `compare` × 10
  - `negation` × 10
  - `out_of_scope` × 10

## 跑分入口

```bash
# 完整环境（PG + Milvus + Doubao）下：
./mvnw -q test -Dtest='EvalRunnerIntegrationTests'

# 单测验证指标计算正确（不需要真实模型）：
./mvnw -q test -Dtest='EvalRunnerTests'
```

EvalRunner 接收 `AgentTurnFacade` + `Dataset`，输出 `Report`：
- `overall.hitRateAt5` ：top-5 命中 ≥1 个 `expectedSpuRefs` 的题目占比
- `overall.precisionAt5` ：每题 `(top-5 ∩ expected) / 5` 的算术平均
- `overall.intentAccuracy` ：`intent.detected.intent` 与期望 intent 字符串相等的占比
- `overall.negationViolationRate` ：仅 `negation` 类目内，top-5 cards 中出现 `mustNotTags` 词的题目占比
- `byCategory.<category>.*` ：同上的分项指标

## 目标线（写进 dataset 的 `metricTargets`）

| 指标 | 目标 |
|---|---|
| `hitRateAt5` | ≥ 0.85 |
| `precisionAt5` | ≥ 0.60 |
| `intentAccuracy` | ≥ 0.90 |
| `negationViolationRate` | ≤ 0.10 |

---

## 历史轮次

### 模板：YYYY-MM-DD HH:MM (commit `<short-sha>` / dataset `w2-regression-2026-05`)

**运行环境**

- LLM：`<model-id>`（例：`doubao-seed-2.0-lite`）
- Embedding：`<embedding-id>`（例：`doubao-embedding-vision`）
- Milvus 集合：`<collection-name>`，索引：`<index-type>`
- 备注：`<例：cold-start / 首次跑 / 调过 prompt 后回归>`

**Overall**

| 指标 | 值 | 目标 | 达标? | 与上一轮 Δ |
|---|---|---|---|---|
| hitRateAt5 | _._ | 0.85 |  | _.__ |
| precisionAt5 | _._ | 0.60 |  | _.__ |
| intentAccuracy | _._ | 0.90 |  | _.__ |
| negationViolationRate | _._ | ≤ 0.10 |  | _.__ |
| avgLatencyMs | _ ms | — | — | _ ms |

**按 category**

| Category | HR@5 | P@5 | IntentAcc | NegViolation | Cases |
|---|---|---|---|---|---|
| basic_recommend | _._ | _._ | _._ | — | 10 |
| filter_by_attr | _._ | _._ | _._ | — | 10 |
| compare | _._ | _._ | _._ | — | 10 |
| negation | _._ | _._ | _._ | _._ | 10 |
| out_of_scope | _._ | _._ | _._ | — | 10 |

**Bottom 5（按 HR@5 / 否定违反 / latency 任一维度排序后取最差）**

| caseId | category | query | 实际 top-5 SPU | 期望 SPU | 异常点 |
|---|---|---|---|---|---|
| `r-…` | `…` | `…` | `[…]` | `[…]` | 例：命中 0；intent 错判为 X；含被排除词 Y |

**变更摘要**

- 与上一轮对比，本轮代码 / 数据 / 模型层的变更：
  - <例：CitationExtractor 关掉 strict 模式，answer 命中率上升>
  - <例：Milvus mustNotTags 表达式新加 brand 桶>
- 复盘 / 下一步：
  - <例：filter_by_attr 价格区间正则漏了「上下」表达，下轮需要补>
  - <例：compare 类目 P@5 偏低，根因是 compare_products 返回的 cards 比期望多>

---

### 示例：2026-05-24 22:00 (commit `0db6da5` / dataset `w2-regression-2026-05`) — baseline 占位

> 占位行，等首次真实跑分时填充。

| 指标 | 值 |
|---|---|
| hitRateAt5 | _ |
| precisionAt5 | _ |
| intentAccuracy | _ |
| negationViolationRate | _ |
| avgLatencyMs | _ ms |

变更摘要：
- 引入 W2 多轮记忆 / 反选 / Compare / Few-shot 反问
- 首次将 50 题回归集落地，目标本轮跑通即可，指标改进留给下一轮

---

## 失败 case 分析模板（每轮可单独附）

对 Bottom 5 中无法当场修掉的 case，按下表跟进：

| caseId | 根因分类 | owner | 计划修复 commit | 状态 |
|---|---|---|---|---|
| `r-…` | retrieval / intent / slot / negation / answer | `@…` | `<待提>` | open |
