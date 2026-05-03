# 05 - API 客户端

> 位于 `io.leavesfly.jharness.integration.api`。本文档说明 JHarness 与 LLM 后端的通信协议、流式解析、重试策略与异常体系。

## 1. 组件结构

```
integration/api/
├── LlmProvider.java              # 抽象接口
├── OpenAiApiClient.java          # OpenAI 兼容协议实现（主力）
├── ApiMessageCompleteEvent.java  # 一次完整响应的聚合结果
├── retry/
│   └── RetryPolicy.java          # 指数退避
└── errors/
    ├── OpenHarnessApiException.java
    ├── RateLimitFailureException.java
    └── AuthenticationFailureException.java
```

> 虽然原 OpenHarness 项目基于 Anthropic，JHarness 选择 **OpenAI 兼容协议** 作为主力实现，因为它被绝大多数开源 / 私有化 LLM 服务兼容（DashScope、DeepSeek、vLLM、Ollama 等）。

## 2. `LlmProvider` 抽象

```java
public interface LlmProvider extends AutoCloseable {
    CompletableFuture<ApiMessageCompleteEvent> streamMessage(
        List<ConversationMessage> messages,
        String systemPrompt,
        List<Map<String, Object>> tools,
        Consumer<StreamEvent> eventConsumer);

    void cancelAllActiveRequests();
    String getProviderName();
    String getModelName();
}
```

**设计目的**（F-P0-1）：把 `QueryEngine` 与具体协议解耦，允许未来挂入 Anthropic 原生、Gemini、Azure 等实现。

## 3. `OpenAiApiClient` 详解

### 3.1 构造与超时

```java
OpenAiApiClient(baseUrl, apiKey, model, maxTokens)
// 或显式超时
OpenAiApiClient(baseUrl, apiKey, model, maxTokens,
                connectTimeoutSec, readTimeoutSec, writeTimeoutSec)
```

默认超时（`DEFAULT_*_TIMEOUT_SECONDS`）：

| 阶段 | 秒 | 说明 |
|------|---|------|
| `connect` | 30 | 建连超时 |
| `read` | 300 | SSE 场景，允许模型慢思考 |
| `write` | 30 | 请求体写入 |

构造时会校验：
- `apiKey` 非空（否则抛 `IllegalArgumentException`）
- 超时均为正数
- 自动推导 `/v1/chat/completions` 端点（支持用户已带 `/chat/completions` 后缀）

### 3.2 `streamMessage` 请求构造

```json
POST {baseUrl}/v1/chat/completions
Authorization: Bearer {apiKey}
Content-Type: application/json

{
  "model": "qwen3-max",
  "max_tokens": 4096,
  "stream": true,
  "messages": [...],
  "tools": [...],            // 由 ToolRegistry.toApiSchema() 生成
  "tool_choice": "auto"
}
```

消息体通过 `ConversationMessage → JsonNode` 转换：

| 内部类型 | OpenAI 字段 |
|---------|------------|
| `TextBlock` | `content: string` / `content[].type=text` |
| `ImageBlock` | `content[].type=image_url` |
| `ToolUseBlock` | `tool_calls[{ id, type=function, function: { name, arguments }}]` |
| `ToolResultBlock` | `role=tool, tool_call_id, content` |

### 3.3 SSE 解析

```
data: {"choices":[{"delta":{"content":"Hello"}}]}
data: {"choices":[{"delta":{"content":" world"}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"bash","arguments":"{\"cmd"}}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\":\"ls\"}"}}]}}]}
data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
data: {"usage":{"prompt_tokens":123,"completion_tokens":45}}
data: [DONE]
```

使用 `okhttp3.sse.EventSources` 订阅，内部维护 `SseResponseCollector`：

- `StringBuilder fullText` — 累积文本内容
- `List<ToolCallAccumulator>` — 按 `index` 合并工具调用的增量 arguments
- `int promptTokens / completionTokens` — usage 快照

每收到 `content` 增量发射 `AssistantTextDelta`，`[DONE]` 后合并出 `List<ContentBlock>` 并完成 `CompletableFuture<ApiMessageCompleteEvent>`。

### 3.4 活跃请求追踪

```java
private final List<EventSource> activeEventSources = synchronizedList(...);
private final List<CompletableFuture<ApiMessageCompleteEvent>> activeFutures = synchronizedList(...);
```

用于：
- `cancelAllActiveRequests()` 遍历取消
- `close()` 释放资源
- **关键修复**：即便 `EventSource.cancel()` 不触发 `onFailure`，也会主动把 future 以 `CancellationException` 完成，避免上游 `.join()` 阻塞。

## 4. `ApiMessageCompleteEvent`

一次完整 LLM 响应的聚合载体：

```java
class ApiMessageCompleteEvent {
    List<ContentBlock> blocks;     // text + tool_use 混合
    UsageSnapshot usage;           // token 消耗
    String finishReason;           // stop / tool_calls / length
}
```

`QueryEngine` 拿到后：
1. `costTracker.addUsage(usage)`
2. 分流 `TextBlock` / `ToolUseBlock`
3. 将 assistant 消息追加到 `messages`

## 5. `RetryPolicy` 指数退避

```java
RetryPolicy.defaultPolicy()
   = new RetryPolicy(maxRetries=3, initialDelayMs=1000, backoffMultiplier=2.0)

// 延迟序列：1s, 2s, 4s
// 总耗时上限约 7s + 三次请求超时
```

### `shouldRetry(attempt, exception)` 策略

| 异常类型 | 是否重试 |
|---------|---------|
| `AuthenticationFailureException` (401) | **否**（fail-fast，避免锁账号） |
| `RateLimitFailureException` (429) | 是 |
| `OpenHarnessApiException` (5xx) | 是 |
| `IOException` / 超时 | 是 |
| `InterruptedException` | 立即抛出，不重试 |

### 中断安全（P2-M7）

```java
try {
    Thread.sleep(delayMs);
} catch (InterruptedException ie) {
    Thread.currentThread().interrupt();
    throw new InterruptedException("重试等待期间被中断");
}
```

恢复中断位 + 立即终止重试循环，让调用方可以感知到取消。

## 6. 异常体系

```
OpenHarnessApiException               ── 5xx / 协议错误基类
  ├─ RateLimitFailureException        ── 429
  └─ AuthenticationFailureException   ── 401
```

`QueryEngine` 层通常让异常向上传播，最终由 `JHarnessApplication#reportFatal` 统一输出：

```
错误前缀: 根因消息
  根因类型: io.leavesfly.jharness.integration.api.errors.RateLimitFailureException  (--debug 下)
```

## 7. 线程与连接池

- **OkHttp 全局单例**：一个 `OkHttpClient` 内含共享 `Dispatcher`（默认 64 并发）和 `ConnectionPool`（keep-alive 5min）。
- **SSE 事件回调线程**：由 OkHttp 调度，不在主线程；`Consumer<StreamEvent>` 的实现应线程安全。
- **关闭策略**：`close()` 会 `executorService.shutdownNow()` + `connectionPool.evictAll()` + 取消 `activeEventSources`。

## 8. 自定义 Provider（进阶）

若需接入不兼容 OpenAI 协议的后端，实现 `LlmProvider`：

```java
public class AnthropicNativeApiClient implements LlmProvider {
    @Override
    public CompletableFuture<ApiMessageCompleteEvent> streamMessage(...) {
        // 走 Anthropic /v1/messages 协议
    }
    @Override public void cancelAllActiveRequests() { ... }
    @Override public String getProviderName() { return "anthropic"; }
    @Override public String getModelName() { return model; }
    @Override public void close() { ... }
}

// 注入 QueryEngine
LlmProvider provider = new AnthropicNativeApiClient(...);
QueryEngine engine = new QueryEngine(provider, toolRegistry, checker, cwd, sys, 8);
```

## 9. 关键配置项

| Settings 字段 / env | 影响 |
|---------------------|-----|
| `model` / `JHARNESS_MODEL` | `"model": ?` 请求字段 |
| `baseUrl` / `OPENAI_BASE_URL` | 端点前缀 |
| `apiKey` / `OPENAI_API_KEY` | `Authorization: Bearer ?` |
| `maxTokens` / `JHARNESS_MAX_TOKENS` | `"max_tokens": ?` |

## 10. 调试技巧

```bash
# 开启 OkHttp + JHarness 详细日志
java -Dlogback.configurationFile=./logback-debug.xml \
     -jar jharness.jar --debug -p "hi"

# 常见日志
API 请求地址: https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
超时(s) connect=30/read=300/write=30
重试第 1 次，等待 1000 ms
尝试 2 失败: HTTP 429 too many requests
```

---

## 🔗 相关文档

- [04-核心引擎 QueryEngine](04-核心引擎-QueryEngine.md) — 调用方
- [06-工具系统](06-工具系统.md) — `tools` 字段来源
- [02-快速开始](02-快速开始.md) — 如何配置 `baseUrl` / `apiKey`
