# 11 · Hook 系统

> 包路径：`io.leavesfly.jharness.capability.hook`
>
> 核心类：`HookExecutor`（编排者） + `HookRegistry` + `HookDefinition` + 5 类 `HookRunner`

---

## 1. 设计目标

Hook（钩子）是一种"在 Agent 生命周期关键节点插入用户自定义逻辑"的机制：

- **生命周期钩子**：会话开始 / 结束 / 用户输入 / 引擎停止 / 子代理结束 / 通知
- **多种执行方式**：本地 Shell / HTTP / 内联 Prompt 注入 / 子代理调用 / Java 子进程
- **结构化输入输出**：通过 JSON 与外部进程通信，支持注入额外 prompt
- **防止递归**：通过环境变量 `JHARNESS_HOOK_DEPTH` 限制嵌套层数（最大 10）
- **安全集成**：与权限系统共用同一栅栏（参见 § 7）

---

## 2. Hook 事件

`HookEvent` 枚举（共 8 类）：

| 事件 | 触发点 | 发射状态 |
|------|--------|---------|
| `SESSION_START` | `QueryEngineBuilder.build()` 装配完毕 | ✅ |
| `SESSION_END` | 进程退出 / `engine.close()` 前 | ✅ |
| `USER_PROMPT_SUBMIT` | `QueryEngine.submitMessage()` 入口（消息入队前） | ✅ |
| `STOP` | `QueryEngine.submitMessage()` 正常返回 / 取消 / 超时 | ✅ |
| `SUBAGENT_STOP` | `AgentOrchestrator.executeSingle()` 返回前 | ✅ |
| `PRE_TOOL_USE` | 工具调用前 | ⚠️ 保留未发射 |
| `POST_TOOL_USE` | 工具调用后 | ⚠️ 保留未发射 |
| `NOTIFICATION` | 通用通知（预算 / MCP 重连等） | ⚠️ 保留未发射 |

**保留但未发射**的事件不会报错，只是不会被触发。

发射方约定：payload 至少包含 `session_id` / `model` / `cwd`，各事件可扩展额外字段：

- `USER_PROMPT_SUBMIT`：`prompt`
- `STOP`：`turns`、`cancelled`、`error`
- `SUBAGENT_STOP`：`agent_name`、`result`

---

## 3. Hook 定义（`HookDefinition`）

```json
{
  "event": "session_start",
  "type": "command",
  "matcher": { "model": "qwen3.5:4b" },
  "command": "/usr/local/bin/notify.sh",
  "args": ["--event", "$EVENT"],
  "env": { "TOKEN": "xxx" },
  "timeoutMs": 5000,
  "blocking": true
}
```

字段：

| 字段 | 必需 | 说明 |
|------|------|------|
| `event` | ✅ | Hook 事件名（lowercase 或 SNAKE_CASE） |
| `type` | ✅ | `command` / `http` / `prompt` / `agent` / `java` |
| `matcher` | 可选 | 条件匹配（按 model / cwd / 自定义键值） |
| `timeoutMs` | 可选 | 单次执行超时（默认 30s） |
| `blocking` | 可选 | true 等待执行完，false 异步触发后立即返回 |

各 type 的额外字段见 § 5。

---

## 4. Hook 注册表

`HookRegistry.register(event, definition)` 按事件分组：

```java
Map<HookEvent, List<Object>> hooksByEvent;

List<HookDefinition> hooks = registry.get(HookEvent.SESSION_START);
```

来源：

1. **全局配置** — `~/.jharness/hooks.json`
2. **项目配置** — `<cwd>/.jharness/hooks.json`
3. **插件** — 各插件的 `hooks.json`（详见 [10-插件与技能系统 § 3.1](10-插件与技能系统.md#31-目录结构)）

---

## 5. Hook Runner（5 类）

`HookExecutor.executeHooks(event, payload)` 按 type 路由到对应 Runner：

| type | Runner 类 | 用途 |
|------|-----------|------|
| `command` | `CommandHookRunner` | 执行本地 Shell |
| `http` | `HttpHookRunner` | 调用 HTTP 端点 |
| `prompt` | `PromptHookRunner` | 注入 prompt 到 LLM 系统消息 |
| `agent` | `AgentHookRunner` | 唤起子代理处理事件 |
| `java` | `JavaSubprocessHookRunner` | fork JVM 子进程执行 Java 类 |

所有 Runner 实现 `HookRunner` 接口：

```java
public interface HookRunner {
    HookResult run(HookDefinition def, HookRunContext ctx);
}
```

### 5.1 CommandHookRunner

执行 Shell 命令，参数 / 环境变量从 payload 注入：

```json
{
  "event": "session_start",
  "type": "command",
  "command": "git",
  "args": ["status", "--short"],
  "env": { "EXTRA": "value" },
  "timeoutMs": 5000
}
```

- 通过 `SubprocessIo.exec(...)` 启动 `ProcessBuilder`
- stdout 限制 64 KB，超出截断
- 退出码非零 → `HookResult.failed`
- **环境变量 `JHARNESS_HOOK_DEPTH`** 由 `HookDepthGuard` 自动 +1，超过 10 直接拒绝执行

### 5.2 HttpHookRunner

发 HTTP POST：

```json
{
  "event": "stop",
  "type": "http",
  "url": "https://my-server/webhook",
  "method": "POST",
  "headers": { "Authorization": "Bearer xxx" },
  "timeoutMs": 5000
}
```

请求体是 payload 的 JSON 序列化。响应 200 时尝试解析为 `HookResult`，否则视为成功但无输出。

URL 经过 `UrlSafetyValidator`（SSRF 防护）：禁止内网 IP、`file://`、`javascript://` 等危险协议。

### 5.3 PromptHookRunner

最轻量的 Runner，直接把 `prompt` 字段作为额外系统消息追加给下一次 LLM 调用：

```json
{
  "event": "user_prompt_submit",
  "type": "prompt",
  "prompt": "（重要约定：所有回复要简短直接）"
}
```

不发起任何 IO，性能最佳。

### 5.4 AgentHookRunner

把事件作为任务派发给指定子代理（参见 [13-多智能体协调](13-多智能体协调.md)）：

```json
{
  "event": "subagent_stop",
  "type": "agent",
  "agentName": "summarizer",
  "task": "总结子代理的执行结果"
}
```

### 5.5 JavaSubprocessHookRunner

fork JVM 子进程执行指定 Java Main 类：

```json
{
  "event": "session_end",
  "type": "java",
  "mainClass": "com.example.MyHook",
  "classpath": "/opt/hooks/my-hook.jar",
  "args": ["--mode", "cleanup"]
}
```

适合"已有 Java 代码资产"的场景，避免重复实现 Shell 包装。

---

## 6. 执行流程

```
QueryEngine.submitMessage(prompt)
   ↓
hookBridge.fire("USER_PROMPT_SUBMIT", payload)
   ↓
HookExecutor.executeHooks(USER_PROMPT_SUBMIT, payload)
   ↓
For each HookDefinition matched by HookMatcher:
   ↓
   route by def.type → 对应 Runner.run(def, ctx)
   ↓
   HookResult { success, output, additionalPrompt }
   ↓
合并所有 additionalPrompt 注入到 prompt 前面（如有）
```

### 6.1 Matcher 条件匹配

`HookMatcher.matches(definition, ctx)`：

- 若 `matcher` 为空 → 命中
- 若 `matcher.model` 存在 → 与 `ctx.payload.model` 相等才命中
- 若 `matcher.cwd` 存在 → 与 `ctx.payload.cwd` 相等才命中
- 其他自定义键值用同样规则

### 6.2 阻塞 vs 非阻塞

- `blocking=true` 默认：串行等待
- `blocking=false`：交给独立线程执行，主流程立即继续

### 6.3 失败处理

单个 Hook 失败被吞掉只记 warn 日志（避免 Hook bug 阻断主流程）；除非 `blocking=true` 且 `def.required=true`（保留字段，目前未启用）。

---

## 7. 与权限系统的集成

Command Hook 子进程在执行前会走 `PermissionChecker` 评估：

- `toolName = "hook:" + def.event`
- `command = def.command + " " + String.join(" ", def.args)`
- `readOnly = false`

`PLAN` 模式下 Command Hook **不会执行**（一律 deny）。

`HookDepthGuard`：通过环境变量 `JHARNESS_HOOK_DEPTH` 防止 Hook 链式触发新 JHarness 进程导致递归爆炸：

```java
public static final int MAX_HOOK_DEPTH = 10;
```

每次 fork 子进程时把当前深度 +1 写入子进程环境变量；启动时若发现已达上限，主进程直接拒绝再发射 Hook。

---

## 8. `/hooks` 命令

| 命令 | 行为 |
|------|------|
| `/hooks list` | 列出所有已注册 hook（按事件分组） |
| `/hooks add <json>` | 运行时添加（不持久化） |
| `/hooks remove <id>` | 移除 |
| `/hooks trigger <event>` | 手动触发（用于调试） |
| `/hooks status` | 摘要 |

由 `HooksCommand.createWithRegistry(registry)` 实现。

---

## 9. 配置示例

`~/.jharness/hooks.json`：

```json
{
  "session_start": [
    {
      "type": "command",
      "command": "/usr/local/bin/notify",
      "args": ["JHarness 启动"],
      "timeoutMs": 3000
    }
  ],
  "user_prompt_submit": [
    {
      "type": "prompt",
      "prompt": "请用中文回答。"
    },
    {
      "type": "http",
      "url": "https://logger.example.com/log",
      "timeoutMs": 2000,
      "blocking": false
    }
  ],
  "stop": [
    {
      "type": "command",
      "command": "/usr/local/bin/save-cost.sh",
      "args": ["$SESSION_ID"]
    }
  ]
}
```

---

## 10. 关键类清单

| 类 | 文件 | 行数 | 职责 |
|----|------|------|------|
| `HookEvent` | `hook/HookEvent.java` | 52 | 事件枚举 + 触发点注释 |
| `HookExecutor` | `hook/HookExecutor.java` | 5.2 KB | 编排者，路由到 Runner |
| `HookRegistry` | `hook/HookRegistry.java` | 64 | 按事件分组注册表 |
| `HookResult` | `hook/HookResult.java` | — | 执行结果 DTO |
| `HookDefinition` | `hook/schemas/HookDefinition.java` | 7.5 KB | Hook 配置模型 |
| `HookRunner` | `hook/runtime/HookRunner.java` | — | Runner 接口 |
| `HookMatcher` | `hook/runtime/HookMatcher.java` | 2.6 KB | 匹配条件 |
| `HookRunContext` | `hook/runtime/HookRunContext.java` | 1.3 KB | 执行上下文 |
| `HookDepthGuard` | `hook/runtime/HookDepthGuard.java` | 1.4 KB | 递归深度防护 |
| `SubprocessIo` | `hook/runtime/SubprocessIo.java` | 2.7 KB | 子进程 IO 公共逻辑 |
| `CommandHookRunner` | `hook/runtime/CommandHookRunner.java` | 6.1 KB | Shell 执行 |
| `HttpHookRunner` | `hook/runtime/HttpHookRunner.java` | 4.4 KB | HTTP 调用 |
| `PromptHookRunner` | `hook/runtime/PromptHookRunner.java` | 1.1 KB | Prompt 注入 |
| `AgentHookRunner` | `hook/runtime/AgentHookRunner.java` | 1.1 KB | 唤起子代理 |
| `JavaSubprocessHookRunner` | `hook/runtime/JavaSubprocessHookRunner.java` | 4.0 KB | Java 子进程 |

---

## 11. 下一步

- 引擎在哪些点发射 Hook → [04-核心引擎 § 9](04-核心引擎-QueryEngine.md#9-hook-集成)
- 插件如何提供 hooks.json → [10-插件与技能系统 § 3.6](10-插件与技能系统.md#36-注入到-jharness)
- 子代理事件 → [13-多智能体协调](13-多智能体协调.md)
