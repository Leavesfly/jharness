# 04 · 核心引擎 QueryEngine

> 包路径：`io.leavesfly.jharness.kernel.engine`
>
> 主类：`QueryEngine`（21 KB / ~570 行）— Agent 的"心脏"。

---

## 1. 职责

`QueryEngine` 是 JHarness 的核心，负责：

1. **管理消息历史**（线程安全的 `List<ConversationMessage>` + 单一锁）
2. **驱动 ReAct 循环**（LLM 调用 ↔ 工具调用 ↔ 工具结果反馈，最多 `maxTurns` 轮）
3. **流式输出事件**（`AssistantTextDelta` / `ToolExecutionStarted` / `ToolExecutionCompleted` / `AssistantTurnComplete`）
4. **取消正在执行的查询**（中断 SSE 连接 + 置位标志使循环退出）
5. **触发 Hook 生命周期**（`USER_PROMPT_SUBMIT` / `STOP`）
6. **协调消息压缩**（委托给 `MessageCompactionService`）
7. **追踪 Token 成本**（委托给 `CostTracker`）
8. **自动持久化会话**（委托给注入的 `SessionPersister`）

它**只依赖三个 SPI 接口**：

| SPI | 实现 | 注入位置 |
|-----|------|----------|
| `LlmGateway` | `OpenAiApiClient` | 构造器参数 `apiClient` |
| `ToolCatalog`（通过 `ToolRegistry`） | `ToolRegistry` | 构造器参数 `toolRegistry` |
| `PermissionGate` | `PermissionChecker` | 构造器参数 `permissionChecker` |

**完全不引用** `integration.api` / `capability.permission` / `tools.builtin` 的任何具体实现，保证内核可独立测试与替换。

---

## 2. 构造与装配

`QueryEngine` 提供两个构造器（旧 / 新签名兼容）：

```java
public QueryEngine(OpenAiApiClient apiClient, ToolRegistry toolRegistry,
                   PermissionGate permissionChecker, Path cwd,
                   String systemPrompt, int maxTurns);

public QueryEngine(LlmGateway apiClient, ToolRegistry toolRegistry,
                   PermissionGate permissionChecker, Path cwd,
                   String systemPrompt, int maxTurns);
```

实际由 `app.bootstrap.QueryEngineBuilder` 统一装配：

```java
QueryEngine engine = new QueryEngineBuilder(settings, permissionMode, resumeSessionId).build();
```

`build()` 内部除了 `new QueryEngine(...)`，还会：

- `engine.getCostTracker().setModelName(...)` + `.setDailyBudgetUsd(...)`
- `engine.setCompactionService(new MessageCompactionService(...).withSystemPromptTokens(sysTokens))`
- `engine.setSessionPersister(snapshots -> SessionStorage.saveSession(...))`
- `engine.setHookEmitter(hookExecutor, sessionId)`（注入 Hook）
- `engine.registerCloseHook(() -> { mcp.close(); cron.close(); task.shutdown(); })`
- `engine.setToolRegistry(...)` 让 MCP 动态工具能反过来刷新到 engine
- `mcpManager.onConnected(() -> registry.refreshMcpTools(mcp))` 补注册 MCP 工具

---

## 3. ReAct 主循环

### 3.1 入口

```java
public CompletableFuture<Void> submitMessage(String prompt, Consumer<StreamEvent> eventConsumer);
```

**调用流程**：

```
submitMessage(prompt, eventConsumer)
   ├─ cancelled.set(false)                                  ← 复位取消标志
   ├─ hookBridge.fire("USER_PROMPT_SUBMIT", {prompt})       ← 入口 Hook
   ├─ messages.add(ConversationMessage.userText(prompt))    ← 入队
   ├─ runQuery(eventConsumer)                               ← 进入 ReAct 主循环
   └─ whenComplete -> hookBridge.fire("STOP", {cancelled, error})
```

### 3.2 ReAct 循环（伪代码）

```
for (int turn = 0; turn < maxTurns; turn++) {
    if (cancelled) break;

    // 1. 压缩（如有必要）
    if (compactionService.needsCompaction(messages)) {
        messages = compactionService.compact(messages);
        persistIfNeeded();
    }

    // 2. 调 LLM
    ApiMessageCompleteEvent resp = apiClient.streamMessage(
        messages, systemPrompt, toolRegistry.toApiSchema(), eventConsumer
    ).join();

    // 3. 累计成本
    costTracker.addUsage(resp.getUsage());

    // 4. 把 assistant 消息（含 text + tool_use）写回历史
    messages.add(resp.toAssistantMessage());
    persistIfNeeded();

    // 5. 若无 tool_use，本轮结束
    List<ToolUseBlock> toolUses = resp.getToolUses();
    if (toolUses.isEmpty()) {
        eventConsumer.accept(new AssistantTurnComplete(...));
        return;
    }

    // 6. PlanModeInterceptor 在 PLAN 模式下提前阻断写操作
    if (planInterceptor.shouldBlock(toolUses)) { ... }

    // 7. 调度工具（单个顺序、多个并行）
    List<ToolResultBlock> results = toolDispatcher.execute(toolUses, eventConsumer);

    // 8. 把 tool_result 写回历史
    messages.add(ConversationMessage.userToolResults(results));
    persistIfNeeded();
}
```

### 3.3 工具调度

委托给 `engine.tools.ToolCallDispatcher`：

| 场景 | 行为 |
|------|------|
| 1 个 `ToolUseBlock` | `executeSingle`（顺序执行） |
| ≥ 2 个 `ToolUseBlock` | `executeParallel`（`CompletableFuture.allOf` 并行 + 5 分钟总超时） |
| 权限不通过 | 返回 `ToolResult.error("权限被拒绝: ...")` |
| 工具内异常 | 转换为 `ToolResult.error(...)` |
| 取消 | 转换为 `ToolResult.error("工具执行被取消")` |
| 超时 | 转换为 `ToolResult.error("工具执行超时")` |

**关键不变量**：返回的 `ToolResultBlock` 数量必须与传入的 `ToolUseBlock` 一一对应（OpenAI API 协议要求），即使工具失败也要补一条错误结果。

### 3.4 计划模式拦截

`PlanModeInterceptor` 在 `PLAN` 权限模式下提前过滤工具调用：

- 凡是 `isReadOnly == false` 的工具调用一律阻断，返回 `ToolResult.error("计划模式下禁止写入操作")`
- 不依赖 `PermissionChecker`，作为快速路径减少进入责任链的开销

### 3.5 已批准计划执行

`PlanStepRunner` 用于 `enter_plan_mode` / `exit_plan_mode` 工具创建的 `ExecutionPlan`：

- 用户在 PLAN 模式下让 AI 输出方案 → 切回 DEFAULT/FULL_AUTO → 由 `PlanStepRunner` 顺序执行每个 `PlanStep`
- 复用 `ToolCallDispatcher.executeToolCall`，保证执行路径与正常工具一致

---

## 4. 流式事件协议

`kernel.engine.stream` 包定义了 4 种事件：

| 事件 | 触发时机 | 字段 |
|------|----------|------|
| `AssistantTextDelta` | LLM 流式文本增量到达 | `text` |
| `ToolExecutionStarted` | 工具开始执行 | `toolName`, `toolUseId` |
| `ToolExecutionCompleted` | 工具结束 | `toolName`, `toolUseId`, `success`, `output` |
| `AssistantTurnComplete` | 一轮 LLM 调用结束（无 tool_use） | `text`, `usage` |

UI 层（`ConsoleInteractiveSession` / `TerminalUI`）通过 `Consumer<StreamEvent>` 实时渲染。

`UsageReport` 是辅助 DTO，描述本轮的 token 消耗。

---

## 5. 消息模型

`kernel.engine.model.ConversationMessage` 由 `MessageRole` + `List<ContentBlock>` 组成：

| 角色 (`MessageRole`) | 典型内容 |
|---------------------|----------|
| `SYSTEM` | 系统提示词（实际放在请求顶层，不入历史） |
| `USER` | `TextBlock`（用户输入）或 `ToolResultBlock`（工具结果） |
| `ASSISTANT` | `TextBlock`（回答）+ `ToolUseBlock`（工具调用） |

`ContentBlock` 是接口，实现：

- **`TextBlock`** — 纯文本
- **`ImageBlock`** — 图片（base64 / URL）
- **`ToolUseBlock`** — LLM 发起的工具调用（`name` / `id` / `input` JSON）
- **`ToolResultBlock`** — 工具执行结果（`toolUseId` / `content` / `isError`）

`UsageSnapshot` 记录 `inputTokens` / `outputTokens` / `cacheReadInputTokens` / `cacheCreationInputTokens`，用于持久化到 `SessionSnapshot`。

---

## 6. 消息压缩

`MessageCompactionService` 实现"按条数 + 按 token 双触发"的压缩：

### 6.1 触发条件

```java
boolean needsCompaction(messages) {
    return messages.size() > maxMessages
        || TokenEstimator.estimateMessages(messages) > maxTokenBudget;
}
```

默认 `maxMessages=20`，`maxTokenBudget=32_000`。可通过 `Settings.messageCompactionTokenBudget` / `Settings.messageCompactionMaxMessages` 覆盖。

### 6.2 压缩策略

1. 取最早的 `summaryMessages`（默认 5）条 → 生成 `[对话摘要] xxx` 文本块
2. 用摘要 + 最新 `maxMessages - 1` 条替换原历史
3. 若仍超 `maxTokenBudget`，迭代将"保留条数"减半直到达标或仅剩 2 条

### 6.3 系统提示词扣减

`withSystemPromptTokens(int sysTokens)` 返回一个新实例，把 `sysTokens` 从总预算中扣除：

```java
new MessageCompactionService().withSystemPromptTokens(TokenEstimator.estimateText(systemPrompt));
```

避免超长 `CLAUDE.md` 把压缩触发时机拖到超出 API 上下文窗口。

---

## 7. 成本与预算

`CostTracker` 用 `AtomicLong/AtomicReference` 追踪：

- `totalInputTokens` / `totalOutputTokens` / `cacheRead` / `cacheCreation`
- `requestCount`
- `sessionCostUsd` — 本会话累计成本
- `dailyCostUsd` / `dailyDate` — 当日累计成本（跨日自动重置）

### 价格表

`ModelPricing.estimateCost(modelName, inTokens, outTokens)` 按模型名查内置价格表（GPT-4o / DeepSeek / Qwen / Kimi 等），输出 `BigDecimal` USD。

### 预算上限

```java
costTracker.setDailyBudgetUsd(BigDecimal.valueOf(5.0));  // 每日 $5 上限
```

超限时 `addUsage` 抛 `BudgetExceededException`（运行时异常），引擎会捕获并通过 `STOP` Hook 上报。

---

## 8. 取消

```java
queryEngine.cancel();
```

- 置 `cancelled = true`
- 调用 `apiClient.cancelAllActiveRequests()` 中断所有活跃 SSE 连接（OkHttp `EventSource.cancel()`）
- 下一轮循环检查 `cancelled` 立即退出
- 当前正在执行的工具：若是 `bash` 等支持中断的工具，子进程会被杀死；否则等当前工具自然结束

`submitMessage` 开始时会复位 `cancelled = false`，确保上次取消不影响本次。

---

## 9. Hook 集成

`HookEmitterBridge` 用反射调用 `capability.hook.HookExecutor`，避免内核包硬依赖能力包：

```java
hookBridge.configure(hookExecutor, sessionId);
hookBridge.fire("USER_PROMPT_SUBMIT", Map.of("prompt", prompt));
hookBridge.fire("STOP", Map.of("cancelled", cancelled, "error", err));
```

QueryEngine 当前发射的事件：

| 事件 | 时机 |
|------|------|
| `USER_PROMPT_SUBMIT` | 用户消息入队前 |
| `STOP` | `runQuery` 完成 / 异常 / 取消 |

`SESSION_START` / `SESSION_END` 由 `QueryEngineBuilder` / `engine.close()` 触发。

`PRE_TOOL_USE` / `POST_TOOL_USE` 目前保留枚举但**未发射**（待工具执行管线增强后接入），插件中声明这些事件不会报错，会被静默跳过。

---

## 10. 会话持久化

```java
engine.setSessionPersister(messages -> {
    SessionSnapshot snap = new SessionSnapshot(sessionId, cwd, model, messages,
        engine.getCostTracker().toUsageSnapshot(), Instant.now(), null, messages.size());
    storage.saveSession(snap);
});
```

QueryEngine 会在每轮消息变更（含压缩 / 工具结果 / 超时 / 取消）后调用一次 `persistIfNeeded()`，异常被吞掉只记 warn 日志，确保持久化失败不影响主循环。

`autoSaveSessions` 配置开关默认开启。

---

## 11. 边界值与约束

| 项 | 默认 | 上限 / 行为 |
|----|------|------------|
| `maxTurns` | 8 | 超出后直接 break，避免 ReAct 失控 |
| 工具并行总超时 | 5 分钟 | 超时返回 `ToolResult.error("工具执行超时")` |
| `MessageCompactionService.maxMessages` | 20 | `needsCompaction` 触发 |
| `MessageCompactionService.maxTokenBudget` | 32 000 | 同上 |
| `MessageCompactionService` 最少保留 | 2 条 | 摘要 + 最新一条用户消息 |
| Hook 递归深度 | 10 (`HookDepthGuard.MAX_HOOK_DEPTH`) | 子进程通过 `JHARNESS_HOOK_DEPTH` 累计 |
| `CostTracker.dailyBudgetUsd` | 0 (不限制) | 超限抛 `BudgetExceededException` |

---

## 12. 关键类清单

| 类 | 文件 | 行数 | 职责 |
|----|------|------|------|
| `QueryEngine` | `engine/QueryEngine.java` | ~570 | ReAct 主循环 |
| `MessageCompactionService` | `engine/MessageCompactionService.java` | 176 | 历史压缩 |
| `CostTracker` | `engine/CostTracker.java` | 185 | Token / USD 追踪 |
| `ModelPricing` | `engine/ModelPricing.java` | ~140 | 各模型价格表 |
| `TokenEstimator` | `engine/TokenEstimator.java` | ~120 | Token 估算 |
| `BudgetExceededException` | `engine/BudgetExceededException.java` | — | 预算超限异常 |
| `ToolCallDispatcher` | `engine/tools/ToolCallDispatcher.java` | 215 | 并行 / 顺序工具调度 |
| `PlanModeInterceptor` | `engine/tools/PlanModeInterceptor.java` | ~80 | PLAN 模式快速阻断 |
| `PlanStepRunner` | `engine/tools/PlanStepRunner.java` | ~90 | 已批准 ExecutionPlan 执行 |
| `HookEmitterBridge` | `engine/hooks/HookEmitterBridge.java` | ~60 | 反射发射 Hook |
| `ConversationMessage` | `engine/model/ConversationMessage.java` | ~80 | 消息模型 |
| `StreamEvent`（接口） | `engine/stream/StreamEvent.java` | — | 流式事件 |

---

## 13. 下一步

- LLM API 实现细节 → [05-API客户端](05-API客户端.md)
- 工具的 JSON Schema 生成与并发模型 → [06-工具系统](06-工具系统.md)
- Hook 五种 Runner 细节 → [11-Hook系统](11-Hook系统.md)
