# 04 - 核心引擎 QueryEngine

> 位于 `io.leavesfly.jharness.core.engine`，是 JHarness 的"心脏"。本文档深入讲解 ReAct 循环、流式事件、消息管理与相关辅助组件。

## 1. 组件全景

| 类 | 职责 |
|----|------|
| `QueryEngine` | 主循环、消息管理、工具分发、取消控制 |
| `CostTracker` | Token / 成本累计，跨日自动重置，预算上限 |
| `ModelPricing` | 模型 → 单价映射表，估算单次调用成本 |
| `TokenEstimator` | 轻量 token 估算（用于压缩前的预判） |
| `MessageCompactionService` | 对话过长时的摘要/截断策略 |
| `BudgetExceededException` | 当日预算超限时抛出 |
| `stream/StreamEvent` | 所有流式事件的标记接口 |
| `model/ConversationMessage` | 含 `role` + `List<ContentBlock>` |
| `model/ContentBlock` 四子类 | `TextBlock` / `ImageBlock` / `ToolUseBlock` / `ToolResultBlock` |

## 2. QueryEngine 核心字段

```java
public class QueryEngine implements AutoCloseable {
    private final List<ConversationMessage> messages = new ArrayList<>();
    private final Object messageLock = new Object();                  // 统一写锁
    private final CostTracker costTracker = new CostTracker();
    private final MessageCompactionService compactionService;
    private final LlmProvider apiClient;                              // 抽象 Provider
    private final ToolRegistry toolRegistry;
    private final PermissionChecker permissionChecker;
    private volatile Path cwd;                                        // 工作目录可运行时切换
    private final String systemPrompt;
    private final int maxTurns;
    private final AtomicBoolean cancelled = new AtomicBoolean(false); // F-P0-3
}
```

**设计要点**：

- **`LlmProvider` 抽象**（F-P0-1）：构造函数同时提供兼容 `OpenAiApiClient` 与通用 `LlmProvider` 两种签名。
- **`messageLock` 单锁**：所有 `messages` 读写统一同步，避免 `Collections.synchronizedList` + 独立锁的双重同步导致死锁。
- **`volatile cwd`**：支持运行时 `/cd` 切换（见 `CdCommandHandler`），切换时会校验路径有效性。
- **`cancelled` 原子标志**：`cancel()` 调用后立即传播到 HTTP 层 (`apiClient.cancelAllActiveRequests()`) 并在下一轮循环出口退出。

## 3. ReAct 循环详解

`QueryEngine#runQuery` 主循环伪代码（真实实现约 150 行）：

```
loop (turn = 0 .. maxTurns-1):
    if cancelled: break

    // 1. 消息压缩（按 token 上限触发）
    compactionService.compactIfNeeded(messages)

    // 2. 调用 LLM（流式）
    ApiMessageCompleteEvent event = apiClient
        .streamMessage(messages, systemPrompt, toolRegistry.toApiSchema(), eventConsumer)
        .get()

    // 3. 成本累计 + 预算校验
    costTracker.addUsage(event.usage)         // 超预算抛 BudgetExceededException

    // 4. 提取 ToolUseBlock
    List<ToolUseBlock> toolCalls = filterToolUses(event.blocks)

    // 5. 追加 assistant 消息
    messages.add(ConversationMessage.assistant(event.blocks))

    // 6. 无工具调用 → 完成
    if toolCalls.isEmpty():
        eventConsumer.accept(AssistantTurnComplete.ok())
        return

    // 7. 权限 + 执行（并行）
    List<ToolResultBlock> results = mode == PLAN
        ? interceptToolCallsForPlan(toolCalls, executionPlan, eventConsumer)
        : executeTools(toolCalls, eventConsumer)

    // 8. 追加 tool 消息
    messages.add(ConversationMessage.tool(results))

    // 9. 回到下一轮
```

达到 `maxTurns` 时发射 `AssistantTurnComplete("已达到最大轮次限制")` 并退出。

## 4. 工具并行执行

`executeTools(List<ToolUseBlock>, consumer)` 对所有调用使用 `CompletableFuture` **并行分发**：

```java
for (ToolUseBlock use : toolCalls) {
    // 1. 发射 ToolExecutionStarted
    consumer.accept(new ToolExecutionStarted(use.getName(), use.getId()));

    // 2. 权限检查
    PermissionDecision decision = permissionChecker.evaluate(
        tool.getName(),
        tool.isReadOnly(input),
        extractFilePath(input),
        extractCommand(input));
    if (decision.isDeny()) { ...失败 short-circuit... }

    // 3. 异步执行
    CompletableFuture<ToolResult> future = tool.execute(input, ctx);
    future.whenComplete((result, err) -> {
        consumer.accept(new ToolExecutionCompleted(name, id, isError));
    });
}
// 等待全部完成（withTimeout）
```

### `extractFilePath` / `extractCommand`

从工具入参 JSON 中提取安全相关字段供权限检查使用：

```java
// 识别 file_path / path / filePath 字段
private String extractFilePath(JsonNode input) { ... }
// 识别 command / cmd 字段
private String extractCommand(JsonNode input) { ... }
```

这两段启发式提取让 `PermissionChecker` 能对通用工具同样执行路径 / 命令维度的安全校验。

## 5. Plan 模式特殊路径

当 `PermissionMode == PLAN` 时，工具调用不会真正执行，而是走 `interceptToolCallsForPlan`：

```
for each toolCall:
    String desc = buildPlanStepDescription(toolCall)   // "写入文件 foo.java"
    executionPlan.addStep(new PlanStep(toolCall, desc))
    result = "[PLAN] 已记录步骤: " + desc
```

用户可通过 `/exit-plan-mode` 退出并 `executeApprovedPlanSteps` 批量执行全部被批准的步骤。

## 6. 消息模型

```
ConversationMessage
  ├─ role: MessageRole { USER, ASSISTANT, SYSTEM, TOOL }
  └─ content: List<ContentBlock>
                │
                ├─ TextBlock       ── text
                ├─ ImageBlock      ── base64 / url（支持多模态）
                ├─ ToolUseBlock    ── { id, name, input: JsonNode }
                └─ ToolResultBlock ── { toolUseId, content, isError }
```

静态工厂：

```java
ConversationMessage.userText(String)
ConversationMessage.assistant(List<ContentBlock>)
ConversationMessage.tool(List<ToolResultBlock>)
ConversationMessage.system(String)
```

该模型与 Anthropic Messages API / OpenAI Tool Calls 的原生 payload 一一对应，序列化由 `Jackson + JacksonUtils.MAPPER` 统一处理。

## 7. 流式事件

| 事件类 | 字段 | 使用者 |
|-------|------|--------|
| `AssistantTextDelta` | `text` | CLI 打字机、TUI TranscriptWidget |
| `ToolExecutionStarted` | `toolName`, `toolUseId` | UI 显示「🔧 执行工具」 |
| `ToolExecutionCompleted` | `toolName`, `toolUseId`, `isError` | UI 显示 ✅ / ❌ |
| `AssistantTurnComplete` | `message` (可空) | UI 换行、打印「已达到最大轮次」 |
| `UsageReport` | `inputTokens`, `outputTokens`, `model`, `costUsd` | 状态栏、`/usage` 命令 |

注册方式：

```java
queryEngine.submitMessage(prompt, event -> {
    switch (event) {
        case AssistantTextDelta d -> render(d.getText());
        case ToolExecutionStarted s -> log("tool start: " + s.getToolName());
        case AssistantTurnComplete c -> println();
        case UsageReport u -> statusBar.updateCost(u);
        default -> { }
    }
}).join();
```

## 8. CostTracker

### 核心数据结构

```java
AtomicLong totalInputTokens, totalOutputTokens, totalCacheReadTokens, totalCacheCreationTokens;
AtomicInteger requestCount;
AtomicReference<BigDecimal> sessionCostUsd;    // 会话累计
AtomicReference<BigDecimal> dailyCostUsd;      // 当日累计
AtomicReference<LocalDate> dailyDate;          // 跨日重置锚点
volatile BigDecimal dailyBudgetUsd;            // <=0 表示不限
volatile String modelName;                      // 价格表 key
```

### `addUsage(UsageSnapshot)` 流程

```
1. 累加 input/output/cache_read/cache_creation token 计数
2. BigDecimal cost = ModelPricing.estimateCost(model, in, out)
3. sessionCostUsd += cost
4. 若 dailyDate != today → dailyCostUsd.set(0), dailyDate.set(today)
5. dailyCostUsd += cost
6. 若 dailyBudget > 0 且 dailyCostUsd > dailyBudget → 抛 BudgetExceededException
```

### 用量摘要输出（CLI 退出时）

```
📊 用量摘要: 请求=12, 输入=8492 tok, 输出=2138 tok, 会话=$0.0427, 今日=$0.1893
```

## 9. MessageCompactionService

当对话历史过长（接近模型 context window）时触发：

- 优先保留最近 N 轮完整消息
- 对更早的消息生成 LLM 摘要 / 截断
- 摘要占位符作为首条系统消息注入

该机制可被 `/compact` 命令手动触发。

## 10. 取消机制（F-P0-3）

```
用户 Ctrl+C / UI "Stop" 按钮
    │
    ▼
queryEngine.cancel()
    ├─ cancelled.set(true)
    └─ apiClient.cancelAllActiveRequests()
           ├─ 遍历 activeEventSources 调用 EventSource.cancel()
           └─ 对 activeFutures 以 CancellationException 强制完成
    │
    ▼
runQuery 循环顶部 if (cancelled.get()) break；
下次 submitMessage 开始时自动 cancelled.set(false) 复位
```

`activeFutures` 的强制完成是关键：SSE 的 `cancel()` 不保证触发 `onClosed/onFailure`，若不主动完成 future，上层 `.join()` 会永久阻塞。

## 11. AutoCloseable 与资源释放

```java
try (QueryEngine engine = buildQueryEngine(settings)) {
    engine.submitMessage(prompt, consumer).join();
}
// engine.close() 自动调用：
//   apiClient.close() → OkHttpDispatcher.shutdown + 清理 active* 集合
```

`ConsoleInteractiveSession` / `TerminalUI` 都位于 `try-with-resources` 内，保证异常退出时 HTTP 连接池 / 线程池都能释放。

## 12. 关键测试点

- `QueryEngineTest` — ReAct 多轮、工具并行、取消逻辑
- `CostTrackerTest` — 原子累计、跨日重置、预算溢出
- `MessageCompactionServiceTest` — 边界条件
- `QueryEngineComprehensiveTest` — 与真实 Mock API 的端到端验证

---

## 🔗 相关文档

- [03-整体架构](03-整体架构.md) — 宏观视角
- [05-API 客户端](05-API客户端.md) — `streamMessage` 内部实现
- [06-工具系统](06-工具系统.md) — 被分发执行的工具
- [08-权限系统](08-权限系统.md) — 每次工具调用前的栅栏
