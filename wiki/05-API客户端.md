# 05 · LLM API 客户端

> 包路径：`io.leavesfly.jharness.integration.api`
>
> 主类：`OpenAiApiClient`（10 KB / 245 行）— OpenAI 兼容协议的 SSE 流式客户端。

---

## 1. 设计目标

| 目标 | 实现 |
|------|------|
| **协议统一** | 全部走 OpenAI Chat Completions 协议（含 `tool_calls`），覆盖 OpenAI / DeepSeek / Qwen / Kimi / Ollama / vLLM |
| **流式输出** | OkHttp `okhttp-sse` 模块实现 Server-Sent Events |
| **可取消** | 通过 `EventSource.cancel()` + `OkHttpClient.dispatcher().cancelAll()` 中断 |
| **可重试** | 指数退避（默认 3 次，初始 1s，倍数 2），认证失败立即放弃 |
| **多 Provider 扩展** | 实现 `LlmGateway` SPI（在 `kernel.spi`），上层可替换实现 |
| **本地端点支持** | `baseUrl` 指向 `localhost` / `127.0.0.1` 时自动用占位 Key |

---

## 2. SPI 与继承关系

```
kernel.spi.LlmGateway                       (kernel 只看接口)
    ↑ extends
integration.api.LlmProvider                 (Provider 抽象层)
    ↑ implements
integration.api.OpenAiApiClient             (具体实现)
```

`LlmGateway` 接口只暴露 4 个方法：

```java
CompletableFuture<ApiMessageCompleteEvent> streamMessage(
    List<ConversationMessage> messages, String systemPrompt,
    List<Map<String, Object>> tools, Consumer<StreamEvent> eventConsumer);

void cancelAllActiveRequests();
String getProviderName();
String getModelName();
default void close() {}                      // AutoCloseable
```

这是从 `LlmProvider` 抽出的 SPI 视图：保留扩展能力，让 `kernel` 不依赖 `integration`。

---

## 3. 类拆分

`OpenAiApiClient` 本身只是"门面"，把职责拆给 `integration.api.openai` 下 5 个子组件：

| 类 | 职责 |
|----|------|
| `OpenAiHttpClient` | 封装 `OkHttpClient`（连接池 / 超时 / 取消）、维护 `activeFutures` 与 `activeEventSources` 集合 |
| `OpenAiUrlResolver` | 归一化 `baseUrl`（自动补 `/chat/completions`）、判断是否本地端点 |
| `OpenAiRequestBuilder` | 构造 OpenAI 请求体（messages / tools / max_tokens / stream） |
| `OpenAiSseStreamReader` | 解析 SSE 事件、累计 tool_calls 增量、生成 `ApiMessageCompleteEvent` |
| `OpenAiErrorClassifier` | 把 HTTP/网络异常分类为 `RateLimitFailureException` / `AuthenticationFailureException` / `OpenHarnessApiException` |

辅助：

| 类 | 用途 |
|----|------|
| `retry.RetryPolicy` | 指数退避策略 |
| `errors.OpenHarnessApiException` | 通用 API 异常基类 |
| `errors.AuthenticationFailureException` | 401，不重试 |
| `errors.RateLimitFailureException` | 429，可重试 |
| `ApiMessageCompleteEvent` | 一次完整调用的结果（文本 + 工具调用 + usage） |

---

## 4. 构造

```java
new OpenAiApiClient(baseUrl, apiKey, model, maxTokens);
new OpenAiApiClient(baseUrl, apiKey, model, maxTokens,
                    connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds);
```

- `baseUrl == null` → 默认 `https://api.openai.com`
- `apiKey` 为空且 `baseUrl` 是本地端点（`localhost` / `127.0.0.1` / `0.0.0.0`） → 用占位 `"ollama"`，方便对接 Ollama
- `apiKey` 为空且非本地端点 → 抛 `IllegalArgumentException`

实际由 `Settings` 注入超时（默认 connect=30s / read=300s / write=30s）。

---

## 5. 请求构造

`OpenAiRequestBuilder.buildRequestBody`：

```json
{
  "model": "qwen3.5:4b",
  "max_tokens": 4096,
  "stream": true,
  "stream_options": { "include_usage": true },
  "messages": [
    { "role": "system", "content": "你是 JHarness..." },
    { "role": "user",   "content": "你好" },
    { "role": "assistant", "content": null,
      "tool_calls": [ { "id": "call_1", "type": "function",
        "function": { "name": "glob", "arguments": "{\"pattern\":\"**/*.java\"}" } } ] },
    { "role": "tool", "tool_call_id": "call_1", "content": "Main.java\nApp.java" }
  ],
  "tools": [
    { "type": "function",
      "function": { "name": "glob", "description": "...",
                    "parameters": { "type": "object", "properties": {...} } } }
  ]
}
```

`tools` 数组由 `ToolRegistry#toApiSchema()` 生成（基于 `BaseTool#toApiSchema` 的 Jakarta Validation 反射）。

---

## 6. SSE 解析

`OpenAiSseStreamReader.handleEvent(String data)` 处理每条 `data:` 事件：

| 事件类型 | 处理 |
|----------|------|
| `[DONE]` | 忽略 |
| 包含 `usage` | 累计 `promptTokens` / `completionTokens` |
| 包含 `error` | 抛 `RuntimeException("API 错误 (xxx): yyy")` |
| `choices[0].delta.content` | 累计到 `fullText`，推送 `AssistantTextDelta(delta)` |
| `choices[0].delta.tool_calls[]` | 用 `ToolCallAccumulator` 增量累计 `name` / `arguments` |
| `choices[0].finish_reason` | 设置完成原因 |

连接结束 (`onClosed`) → `finalizeCollection` + `buildCompleteEvent`：

```java
new ApiMessageCompleteEvent(
    fullText.toString(),
    accumulators.stream().map(ToolCallAccumulator::toBlock).toList(),
    new UsageSnapshot(promptTokens, completionTokens, 0, 0)
);
```

---

## 7. 重试策略

`RetryPolicy.defaultPolicy()` = `new RetryPolicy(3, 1000ms, 2.0)`，对应延迟序列 `1s → 2s → 4s`。

```java
public boolean shouldRetry(int attempt, Exception ex) {
    if (attempt >= maxRetries) return false;
    if (ex instanceof AuthenticationFailureException) return false;  // 401 立即放弃
    return true;
}
```

`OpenAiApiClient.streamMessageWithRetry(...)` 在 `EventSource.onFailure` 中检查策略，命中则 `CompletableFuture.delayedExecutor` 延迟后递归。

`Thread.sleep` 被中断时会恢复中断位并立即终止重试（避免业务方 `cancel()` 后还在等下次重试）。

---

## 8. 错误分类

`OpenAiErrorClassifier.classify(throwable, response)`：

| HTTP 状态 | 异常类型 |
|-----------|----------|
| 401 / 403 | `AuthenticationFailureException` |
| 429 | `RateLimitFailureException` |
| 5xx | `OpenHarnessApiException` (`retryable=true`) |
| 网络 IO / SocketTimeout | `OpenHarnessApiException` (`retryable=true`) |
| 其他 | `OpenHarnessApiException` |

---

## 9. 取消

```java
@Override
public void cancelAllActiveRequests() {
    httpClient.cancelAll();   // 取消所有 EventSource + Dispatcher 中的待发请求
}
```

`OpenAiHttpClient`：

- `registerFuture(future)` / `registerEventSource(es)` — 加入活跃集合
- `unregisterEventSource(es)` — 在 `onClosed` / `onFailure` 调用
- `cancelAll()` — 遍历 `activeEventSources`，逐个 `cancel()`，并调用 `dispatcher.cancelAll()`

`QueryEngine.cancel()` → `apiClient.cancelAllActiveRequests()` → SSE 立即断开 → `onFailure` 报 `IOException("Canceled")` → 不进入重试（取消异常被识别为终态）。

---

## 10. 本地端点自适应

`OpenAiUrlResolver.isLocalEndpoint(baseUrl)` 用正则匹配 `localhost` / `127.0.0.1` / `0.0.0.0`。

| baseUrl | API Key | 行为 |
|---------|---------|------|
| `https://api.openai.com/v1` | 设置 | 正常 |
| `https://api.openai.com/v1` | 未设置 | 启动失败：抛 `IllegalArgumentException` |
| `http://localhost:11434/v1` | 设置 | 用真实 Key |
| `http://localhost:11434/v1` | 未设置 | 用占位 `"ollama"`，warn 日志 |

`JHarnessApplication` 在启动前也会做一遍同样判断（见 `call()` 方法），让错误尽早暴露。

---

## 11. URL 归一化

`OpenAiUrlResolver.buildCompletionsUrl(baseUrl)`：

| 输入 | 输出 |
|------|------|
| `https://api.openai.com` | `https://api.openai.com/v1/chat/completions` |
| `https://api.openai.com/v1` | `https://api.openai.com/v1/chat/completions` |
| `https://api.openai.com/v1/chat/completions` | （原样保留） |
| `http://localhost:11434/v1` | `http://localhost:11434/v1/chat/completions` |

允许用户配置任意层级的 `baseUrl`，框架自动补齐。

---

## 12. 完整调用示例

```java
OpenAiApiClient client = new OpenAiApiClient(
    "https://api.deepseek.com/v1", "sk-xxx", "deepseek-chat", 4096);

List<ConversationMessage> messages = List.of(
    ConversationMessage.userText("总结一下 README.md")
);

CompletableFuture<ApiMessageCompleteEvent> future = client.streamMessage(
    messages,
    "你是 JHarness，一个 AI 编程助手。",
    toolRegistry.toApiSchema(),
    event -> {
        if (event instanceof AssistantTextDelta delta) {
            System.out.print(delta.getText());
        }
    }
);

ApiMessageCompleteEvent result = future.join();
System.out.println("\n完整文本: " + result.getText());
System.out.println("工具调用数: " + result.getToolUses().size());
System.out.println("Token: " + result.getUsage());
```

---

## 13. 自定义 Provider

要接入"非 OpenAI 协议"的 LLM（如 Anthropic 原生 / Gemini）：

1. 实现 `kernel.spi.LlmGateway` 接口
2. 在 `app.bootstrap.QueryEngineBuilder` 中将 `apiClient` 替换为新实现：

```java
LlmGateway apiClient = new MyAnthropicClient(...);
QueryEngine engine = new QueryEngine(apiClient, toolRegistry, permissionChecker, cwd, systemPrompt, maxTurns);
```

`QueryEngine` 的所有调用都通过 `LlmGateway` 接口，**不需要修改内核任何代码**。

详见 [17-扩展开发指南 § 3](17-扩展开发指南.md#3-自定义-llm-provider)。

---

## 14. 关键类清单

| 类 | 文件 | 行数 | 职责 |
|----|------|------|------|
| `OpenAiApiClient` | `api/OpenAiApiClient.java` | 245 | 门面，组装 4 子组件 + 重试 |
| `LlmProvider` | `api/LlmProvider.java` | ~80 | extends `LlmGateway` |
| `ApiMessageCompleteEvent` | `api/ApiMessageCompleteEvent.java` | — | 一次调用完整结果 DTO |
| `OpenAiHttpClient` | `api/openai/OpenAiHttpClient.java` | 5.3 KB | OkHttp 池 + 活跃请求跟踪 |
| `OpenAiUrlResolver` | `api/openai/OpenAiUrlResolver.java` | 2.5 KB | URL 归一化 |
| `OpenAiRequestBuilder` | `api/openai/OpenAiRequestBuilder.java` | 7.2 KB | 请求体构造 |
| `OpenAiSseStreamReader` | `api/openai/OpenAiSseStreamReader.java` | 158 | SSE 解析 |
| `OpenAiErrorClassifier` | `api/openai/OpenAiErrorClassifier.java` | 1.7 KB | 错误分类 |
| `RetryPolicy` | `api/retry/RetryPolicy.java` | 108 | 指数退避 |
| `OpenHarnessApiException` | `api/errors/OpenHarnessApiException.java` | — | 异常基类 |

---

## 15. 故障排查

| 现象 | 原因 | 修复 |
|------|------|------|
| `401 Unauthorized` | API Key 错 | 检查 `OPENAI_API_KEY` |
| `429 Too Many Requests` 反复重试失败 | 限流 | 降低请求频率，调大 `RetryPolicy.maxRetries` |
| SSE 中途 `IOException` | 网络抖动 | `RetryPolicy` 自动处理；网络极差调大 `readTimeoutSeconds` |
| 工具调用 `arguments` 解析失败 | 流式拼接漏接 | `ToolCallAccumulator` 已处理增量；如仍失败检查模型是否原生支持 `tool_calls` |
| 本地 Ollama 连不上 | Ollama 未启动 | `ollama serve` |

---

## 16. 下一步

- 工具如何被打包成 `tools` 字段送给 LLM → [06-工具系统 § 5](06-工具系统.md#5-jsonschema-自动生成)
- 引擎如何处理返回的 `tool_calls` → [04-核心引擎 § 3.3](04-核心引擎-QueryEngine.md#33-工具调度)
- 扩展自己的 Provider → [17-扩展开发指南 § 3](17-扩展开发指南.md#3-自定义-llm-provider)
