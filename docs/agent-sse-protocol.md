# Agent SSE Protocol

`POST /public/agent/turn`

Request:

```json
{
  "userId": "u1",
  "conversationId": "c1",
  "message": "推荐 300 元以下的双肩包",
  "turnId": "t-001"
}
```

Response content type: `text/event-stream`.

Each SSE frame uses:

- `id`: per-event UUID, not persisted in W1
- `event`: event name below
- `comment`: `correlationId`
- `data`: JSON payload

Events:

| event | data |
|---|---|
| `turn.started` | `{ "turnId", "conversationId", "model" }` |
| `intent.detected` | `{ "intent", "confidence", "source", "slots" }` |
| `tool.calling` | `{ "toolName", "args" }` |
| `tool.result` | `{ "toolName", "cards", "facetsApplied" }` |
| `answer.delta` | `{ "text" }` |
| `citation` | `{ "refId", "spuId", "chunkId" }` |
| `notice` | `{ "code", "message", "severity" }` |
| `turn.completed` | `{ "turnId", "latencyMs", "tokensIn", "tokensOut", "generatedByModel" }` |
| `turn.error` | `{ "code", "message", "recoverable" }` |

Expected W1 happy-path order:

```text
turn.started
intent.detected
tool.calling
tool.result
answer.delta *
citation *
turn.completed
```
