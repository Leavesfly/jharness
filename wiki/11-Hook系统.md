# 11 - Hook 系统

> 位于 `io.leavesfly.jharness.agent.hooks`。Hook 是在 Agent 生命周期关键节点执行的**外部动作**，可用于审计、拦截、自动化、合规校验。

## 1. 设计目标

- **事件驱动**：固定的几个生命周期事件，每个事件挂多个 Hook
- **可阻断**：Hook 执行结果可以阻止 Agent 继续操作
- **多形态**：shell 命令 / 模型提问 / HTTP 回调 / 子 Agent
- **安全优先**：注入防御、递归防御、超时防御、输出爆炸防御

## 2. 核心类

| 类 | 作用 |
|---|------|
| `HookEvent` | 生命周期事件枚举 |
| `HookDefinition` | 抽象 Hook 定义（Jackson 多态） |
| `HookRegistry` | 按事件存放 Hook 的注册表 |
| `HookExecutor` | 异步执行 Hook，返回 `HookResult` |
| `HookResult` | 执行结果 |

## 3. `HookEvent`：事件枚举

典型事件（由 `QueryEngine` / `ToolExecutor` 触发）：

- `PRE_TOOL_USE` — 工具调用前
- `POST_TOOL_USE` — 工具调用后
- `USER_PROMPT_SUBMIT` — 用户输入提交时
- `ASSISTANT_MESSAGE` — Assistant 消息产出后
- `SESSION_START` / `SESSION_END`
- （具体枚举值以 `HookEvent.java` 为准）

## 4. `HookDefinition`：四种子类

用 `@JsonTypeInfo(use = Id.NAME, property = "type")` 做多态反序列化：

| 子类 | `type` | 关键字段 | 默认阻断 |
|------|--------|----------|----------|
| `CommandHookDefinition` | `command` | `command`、`timeoutSeconds=30` | `false` |
| `PromptHookDefinition` | `prompt` | `prompt`、`model`、`timeoutSeconds=30` | `true` |
| `HttpHookDefinition` | `http` | `url`、`headers`、`timeoutSeconds=30` | `false` |
| `AgentHookDefinition` | `agent` | `prompt`、`model`、`timeoutSeconds=60` | `true` |

**公共字段**：
- `matcher`：匹配表达式（如工具名），不匹配就跳过该 Hook
- `blockOnFailure`：失败时是否阻断 Agent

### 4.1 示例配置（`hooks.json`）

```json
{
  "pre_tool_use": [
    {
      "type": "command",
      "matcher": "bash",
      "command": "echo \"[audit] about to run bash\" >&2",
      "timeout_seconds": 5,
      "block_on_failure": false
    },
    {
      "type": "prompt",
      "matcher": "write|edit",
      "prompt": "检查本次编辑是否会泄露密钥，若有则回 DENY",
      "model": "qwen3-coder",
      "block_on_failure": true
    }
  ],
  "post_tool_use": [
    {
      "type": "http",
      "url": "https://audit.example.com/tool",
      "headers": { "Authorization": "Bearer X" },
      "timeout_seconds": 10
    }
  ]
}
```

## 5. `HookRegistry`：注册表

```java
public class HookRegistry {
    private final Map<HookEvent, List<Object>> hooksByEvent;   // 每事件独立列表

    void register(HookEvent event, Object hook);               // 注册
    List<Object> get(HookEvent event);                         // 取全部
    String summary();                                          // 摘要：事件 → 数量
    void clear();
}
```

注意 value 类型是 `List<Object>`：既可放 `HookDefinition`，也兼容原始 Map（插件通过 `hooks.json` 直接塞入反序列化后的 `Map` 时仍可工作，`HookExecutor` 会再做一次类型分发）。

## 6. `HookExecutor`：执行引擎

### 6.1 签名

```java
CompletableFuture<List<HookResult>> execute(HookEvent event, Map<String,Object> payload);
```

异步返回所有 Hook 的结果列表（顺序与注册顺序一致）。

### 6.2 递归深度防御（S-2）

Prompt/Agent Hook 会 fork 子进程调用 JHarness 本身，一旦子进程又触发 Hook 即 **Hook 风暴**。防御代码：

```java
private static final int MAX_HOOK_DEPTH = /* 配置 */;
private static final ThreadLocal<Integer> HOOK_DEPTH = new ThreadLocal<>();

int enterDepth = currentDepth();     // ThreadLocal 或 env JHARNESS_HOOK_DEPTH
if (enterDepth >= MAX_HOOK_DEPTH) {
    return CompletableFuture.completedFuture(List.of(
        new HookResult("depth-guard", false, null, true,
                       "Hook 递归深度超过上限 " + MAX_HOOK_DEPTH + "，已阻止继续触发")));
}

HOOK_DEPTH.set(enterDepth + 1);
try { /* 执行 */ } finally { HOOK_DEPTH.remove(); }
```

fork 子进程时会把 `JHARNESS_HOOK_DEPTH` 写入环境变量，确保 **跨进程** 深度累计不被绕过。

### 6.3 Command Hook 安全策略（S-2 核心）

**关键原则：LLM 可控的 payload 绝不拼进命令行参数**，只通过三种安全通道：

1. **stdin**：整条 payload 以 JSON 字符串写入子进程标准输入
2. **环境变量 `OPENHARNESS_HOOK_PAYLOAD`**：完整负载
3. **环境变量 `JHARNESS_ARGUMENTS`**：渲染后的 arguments 段

另设：
- `OPENHARNESS_HOOK_EVENT`：事件名
- `JHARNESS_HOOK_DEPTH`：递归深度

管理员如要在命令模板中使用，**必须加双引号**：

```bash
# 正确
echo "$JHARNESS_ARGUMENTS" | jq .

# 危险 —— 将被启发式规则 DANGEROUS_ARG_EMBED 拒绝
result=$(curl ${JHARNESS_ARGUMENTS})
result=`grep ${JHARNESS_ARGUMENTS} log`
```

拒绝逻辑：

```java
if (DANGEROUS_ARG_EMBED.matcher(command).find()) {
    return new HookResult(hook.getType(), false, null, true,
        "命令模板将 payload 嵌入到命令替换 $(...) / `...` 中，存在注入风险，已拒绝");
}
```

### 6.4 与 PermissionChecker 联动（FP-3）

```java
public void setPermissionChecker(PermissionChecker permissionChecker) { ... }
```

注入后，Command Hook 在执行前还要过一遍 `PermissionChecker` 的命令黑名单与工具黑白名单。即使 `hooks.json` 由管理员预定义，也**不能绕过**集团级的命令禁用策略（如 `sudo rm`）。

### 6.5 超时与输出控制

- 超时后先 `Process#destroy`，必要时 `destroyForcibly` 防止子进程残留
- 输出累计字节数设上限，超过就截断并打上 `[OUTPUT_TRUNCATED]`
- stderr 与 stdout 分流记录

### 6.6 Prompt / Agent Hook

- `Prompt`：调用一次 LLM，要求其仅回答 ALLOW / DENY / REASON，用于轻量条件判断
- `Agent`：fork 一个完整的 JHarness 子会话执行更复杂的验证逻辑（默认超时 60s）

两者都默认 `blockOnFailure=true`，失败会阻断 Agent 继续。

### 6.7 HTTP Hook

- 使用 `UrlSafetyValidator.validate(url)` 过 SSRF 防御（详见 [08-权限系统 §9](08-权限系统.md)）
- 仅允许 `http` / `https`
- POST 事件负载 + 自定义 Headers，默认 30s 超时
- 响应非 2xx 视为失败

## 7. `HookResult`：执行结果

```java
public class HookResult {
    String hookType;    // command / prompt / http / agent / depth-guard
    boolean success;
    String output;      // 可读输出（stdout / LLM 回复 / HTTP body）
    boolean blocked;    // 是否要求阻断
    String reason;      // 阻断原因或错误信息
}
```

消费方（`QueryEngine` / `ToolExecutor`）的典型决策：

```java
List<HookResult> results = executor.execute(PRE_TOOL_USE, payload).join();
for (HookResult r : results) {
    if (r.isBlocked()) {
        abortToolCall(r.getReason());
        return;
    }
}
```

## 8. Hook 注册的三个来源

| 来源 | 时机 | 入口 |
|------|------|------|
| 用户配置 | 启动时 | `~/.jharness/hooks.json` → `HookLoader` |
| 项目配置 | 启动时 | `<cwd>/.jharness/hooks.json` |
| 插件 | 启动时 | `LoadedPlugin.hooks` → 合入全局 Registry |

合并策略：**追加**（同事件的多个 Hook 按顺序全部执行），不做去重。

## 9. 与 QueryEngine 的联动点

```
QueryEngine.processUserInput
  ├─ USER_PROMPT_SUBMIT ——→ Hook
  └─ loop:
       ├─ LLM streaming
       ├─ 对每个 tool_use:
       │    ├─ PRE_TOOL_USE  ——→ Hook（可阻断）
       │    ├─ permissionChecker.evaluate
       │    ├─ tool.execute
       │    └─ POST_TOOL_USE ——→ Hook（记录 / 审计）
       └─ ASSISTANT_MESSAGE  ——→ Hook
QueryEngine.end
  └─ SESSION_END             ——→ Hook
```

## 10. `/hooks` 命令

由 `HookCommandHandler.createHooksCommand` 注册：

- `/hooks list` — 列出所有已注册 Hook（按事件）
- `/hooks reload` — 重新加载
- 其余由实现类决定

## 11. 最佳实践

1. **命令 Hook 只做只读审计**：打日志、写指标、发通知
2. **拦截性校验用 Prompt Hook**：让模型判断是否放行
3. **跨系统集成用 HTTP Hook**：更易与安全中台对接
4. **Agent Hook 代价高慎用**：每次都 fork 新会话，只在关键节点
5. **务必设置合理的 `timeout_seconds`**：默认值（30/60s）对大多数场景偏大

---

## 🔗 相关文档

- [08-权限系统](08-权限系统.md) — PermissionChecker 注入
- [10-插件与技能系统](10-插件与技能系统.md) — 插件如何提供 hooks.json
- [04-核心引擎 QueryEngine](04-核心引擎-QueryEngine.md) — 事件触发点
