# JHarness

> Java 实现的轻量级 AI Agent 框架 —— OpenHarness 的 Java 版本。

[![Java](https://img.shields.io/badge/Java-17%2B-blue)]()
[![Maven](https://img.shields.io/badge/Maven-3.6.3%2B-orange)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-green)]()

JHarness 是一个开箱即用的 AI 编程助手框架，提供 **ReAct 查询引擎**、**40+ 内置工具**、**60+ 斜杠命令**、**多智能体协调**、**MCP 协议客户端**、**插件 & 技能扩展**、**Hook 生命周期**、**Cron 定时任务**等能力。默认对接本地 Ollama，也兼容所有 OpenAI 协议的远端服务（DeepSeek / Qwen / Kimi / DashScope / vLLM 等）。

---

## ✨ 核心特性

- **🚀 开箱即用**：默认对接本地 Ollama (`qwen3.5:4b`)，无需 API Key 即可启动
- **🔌 多 Provider**：实现 `LlmGateway` SPI，可扩展任意 OpenAI 兼容协议
- **🛠️ 40+ 内置工具**：覆盖文件、代码智能 (LSP)、Shell、网络、任务、Agent、MCP、Cron 全场景
- **⌨️ 60+ 斜杠命令**：交互式命令体系（`/help` `/model` `/permissions` `/git` `/mcp` `/cron` …）
- **🔒 多层权限系统**：三种模式 × 工具白/黑名单 × 路径规则 × 命令黑名单 × 责任链评估
- **🤖 多智能体协调**：并行 / 顺序执行多 Agent，支持 Team 角色管理
- **🌐 MCP 协议客户端**：内置 stdio + HTTP 双传输的 MCP 客户端，动态发现远端工具
- **🎯 插件 & 技能体系**：Claude Code 兼容的 plugin manifest + Markdown YAML frontmatter 技能
- **🔗 Hook 生命周期**：`SESSION_START` / `STOP` / `USER_PROMPT_SUBMIT` / `SUBAGENT_STOP` 等事件钩子
- **💾 会话快照 & 记忆**：JSON 持久化的会话快照 + 跨会话分类记忆
- **⏰ Cron 定时任务**：基于 `ScheduledExecutorService` 的定时调度
- **🧠 上下文管理**：消息压缩 (token 预算) + CLAUDE.md 项目上下文注入
- **🛡️ 架构守卫**：ArchUnit + Maven Enforcer 保证分层依赖方向不退化

---

## 🚀 快速开始

### 环境要求

- **Java 17+**
- **Maven 3.6.3+**
- （可选）本地 [Ollama](https://ollama.com) 或任意 OpenAI 兼容端点

### 构建

```bash
git clone <repo-url>
cd jharness
mvn clean package
```

### 运行

**模式 1：本地 Ollama（默认，无需 API Key）**

```bash
ollama pull qwen3.5:4b
java -jar target/jharness-0.1.0-SNAPSHOT.jar
```

**模式 2：远端 OpenAI 兼容 API**

```bash
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://api.deepseek.com/v1
export OPENAI_MODEL=deepseek-chat
java -jar target/jharness-0.1.0-SNAPSHOT.jar
```

**模式 3：单次查询**

```bash
java -jar target/jharness-0.1.0-SNAPSHOT.jar -p "解释 src/main/java/io/leavesfly/jharness/JHarnessApplication.java"
```

**模式 4：Lanterna TUI**

```bash
java -jar target/jharness-0.1.0-SNAPSHOT.jar --tui
```

### 常用 CLI 选项

| 选项 | 说明 |
|------|------|
| `-p, --print <prompt>` | 单次查询模式 |
| `-m, --model <name>` | 临时指定模型 |
| `-d, --debug` | 调试日志 |
| `-c, --continue` | 继续上一次会话 |
| `-r, --resume <id>` | 恢复指定会话 |
| `--permission-mode <m>` | `default` / `full_auto` / `plan` |
| `--max-turns <n>` | 最大 ReAct 轮数（默认 8） |
| `--output-format <fmt>` | `text` / `json` / `stream-json` |
| `--tui` | 启用 Lanterna 终端界面 |
| `--skip-onboarding` | 跳过首次启动的 Agent Onboarding 向导（CI / 脚本场景） |

### 🧭 首次启动 · Agent Onboarding

首次在交互式终端启动 JHarness 时，若 `~/.jharness/.onboarded` 标记不存在且当前未配置可用凭据（API Key 为空且 baseUrl 非本地端点），会自动进入 **Agent Onboarding 向导**：

1. **选择模型来源**：本地 Ollama（默认） / 远端 OpenAI 兼容端点 / 跳过；
2. **填写 Base URL 与模型名**（带默认值，回车即接受）；
3. **API Key 输入**（脱敏显示当前值；输入 `-` 显式清空）；
4. **权限模式**：`DEFAULT` / `FULL_AUTO` / `PLAN`。

完成后写入 `~/.jharness/settings.json` 并创建 `.onboarded` 标记，下次启动自动跳过。

| 行为 | 操作 |
|------|------|
| 重新触发向导 | 交互式中执行 `/onboarding`（清除 marker） → 重启 |
| 一次性跳过 | `--skip-onboarding` |
| 永久跳过 | 向导第一步选 `3) 跳过`（会写入 marker） |
| CI / 重定向 / IDE Run | 自动跳过（`System.console() == null`） |

---

## 🏗️ 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                    UI 层 (ui + app.cli)                       │
│   TerminalUI(Lanterna) · ConsoleInteractiveSession · CLI     │
├─────────────────────────────────────────────────────────────┤
│                       内核层 (kernel)                         │
│  QueryEngine ─ ReAct 循环 / SSE 流式 / 工具调度 / 取消         │
│    ├─ MessageCompactionService (token 预算压缩)               │
│    ├─ CostTracker / ModelPricing / TokenEstimator             │
│    ├─ ToolCallDispatcher (并行 + 权限 + 超时)                 │
│    ├─ PlanStepRunner / PlanModeInterceptor                    │
│    └─ HookEmitterBridge                                       │
│  spi: LlmGateway · ToolCatalog · PermissionGate (反向依赖隔离)│
│  memory · edit · plan · state                                 │
├──────────────┬───────────────────┬──────────────────────────┤
│ capability   │ extension         │ integration              │
│ ─ hook       │ ─ plugins         │ ─ api (OpenAI/SSE/重试)  │
│ ─ permission │ ─ skills          │ ─ mcp (stdio + HTTP)     │
│ ─ session    │                   │ ─ cron                   │
│ ─ task       │                   │ ─ bridge                 │
│ ─ coordination                                               │
├─────────────────────────────────────────────────────────────┤
│           tools.builtin (40+)    ·    command.builtin (60+)  │
│   file · shell · web · code · task · agent · mcp · cron ...  │
└─────────────────────────────────────────────────────────────┘
```

### 核心 ReAct 流程

```
用户输入 → QueryEngine.submitMessage(prompt)
        → USER_PROMPT_SUBMIT Hook
        → LLM 流式调用（OpenAiApiClient + SSE）
        → 解析 ContentBlock (Text / ToolUse)
        → ToolCallDispatcher 并行执行工具
            ├─ PermissionChecker 责任链评估
            ├─ 异步执行（CompletableFuture）
            └─ ToolResultBlock 写回消息
        → STOP Hook
        → 循环至无 ToolUse / 达到 maxTurns / 被 cancel
        → SessionPersister 自动保存快照
```

---

## 📦 项目结构

```
src/main/java/io/leavesfly/jharness/
├── JHarnessApplication.java         # Picocli 主入口（仅 CLI 选项 + 编排）
│
├── app/                             # 应用引导
│   ├── bootstrap/
│   │   ├── QueryEngineBuilder.java  # 装配 QueryEngine 全链路
│   │   ├── PluginBootstrap.java     # 插件加载 + Agent 注册
│   │   └── McpBootstrap.java        # MCP 注册 + CLAUDE.md 注入
│   └── cli/
│       ├── CliRunners.java          # print / interactive 模式入口
│       ├── CliConsole.java
│       └── SessionRestorer.java     # --continue / --resume
│
├── kernel/                          # 内核层
│   ├── engine/
│   │   ├── QueryEngine.java         # ReAct 循环主体
│   │   ├── CostTracker.java         # token / USD 追踪
│   │   ├── ModelPricing.java
│   │   ├── TokenEstimator.java
│   │   ├── MessageCompactionService.java
│   │   ├── BudgetExceededException.java
│   │   ├── model/                   # ConversationMessage / ContentBlock / ...
│   │   ├── stream/                  # StreamEvent / AssistantTextDelta / ...
│   │   ├── tools/                   # ToolCallDispatcher / PlanStepRunner / PlanModeInterceptor
│   │   └── hooks/HookEmitterBridge.java
│   ├── spi/                         # SPI（kernel 不依赖具体实现）
│   │   ├── LlmGateway.java
│   │   ├── ToolCatalog.java
│   │   └── PermissionGate.java
│   ├── memory/MemoryManager.java
│   ├── edit/                        # EditHistoryManager / EditRecord / DiffUtils
│   ├── plan/                        # ExecutionPlan / PlanStep
│   └── state/                       # AppState / AppStateStore
│
├── integration/                     # 外部集成
│   ├── api/                         # LLM API
│   │   ├── OpenAiApiClient.java     # implements LlmProvider extends LlmGateway
│   │   ├── ApiMessageCompleteEvent.java
│   │   ├── openai/                  # HttpClient/UrlResolver/RequestBuilder/SseReader/ErrorClassifier
│   │   ├── retry/RetryPolicy.java
│   │   └── errors/                  # RateLimit / Authentication 等
│   ├── mcp/
│   │   ├── McpClientManager.java
│   │   ├── types/                   # McpServerConfig / McpConnectionStatus
│   │   └── session/                 # StdioMcpSession / HttpMcpSession / McpJsonRpc
│   ├── cron/CronRegistry.java
│   └── bridge/                      # BridgeSessionManager / WorkSecretHelper
│
├── tools/                           # 工具系统
│   ├── BaseTool.java
│   ├── ToolRegistry.java
│   ├── ToolResult.java / ToolExecutionContext.java
│   ├── input/                       # 各工具输入 DTO
│   └── builtin/                     # 40+ 工具
│       ├── file/    # FileRead/Write/Edit/MultiEdit/UndoEdit/Glob/Grep
│       ├── shell/   # Bash/Sleep + session/
│       ├── web/     # WebFetch/WebSearch
│       ├── code/    # LSP/NotebookEdit
│       ├── task/    # TodoWrite + TaskCreate/Get/List/Stop/Output/Update
│       ├── agent/   # Agent/SendMessage/TeamCreate/TeamDelete/AskUserQuestion
│       ├── mcp/     # ListMcpResources/ReadMcpResource/McpAuth/McpToolAdapter
│       ├── cron/    # CronCreate/List/Delete/RemoteTrigger
│       ├── mode/    # EnterPlanMode/ExitPlanMode/EnterWorktree/ExitWorktree
│       ├── meta/    # Brief/Skill/ToolSearch
│       └── config/  # ConfigTool
│
├── command/                         # 斜杠命令
│   ├── SlashCommand.java / CommandContext.java / CommandResult.java
│   ├── CommandRegistry.java
│   ├── SimpleSlashCommand.java
│   ├── PluginSlashCommand.java
│   ├── keybindings/
│   └── builtin/
│       ├── git/    · agent/    · config/    · session/    · system/
│
├── extension/
│   ├── plugins/  # PluginLoader / PluginManifest / PluginInstaller / trust / marketplace
│   └── skills/   # SkillLoader / SkillRegistry / SkillDefinition
│
├── capability/                      # 能力模块（被 kernel 通过 SPI 引用）
│   ├── hook/                        # HookEvent / HookExecutor / HookRegistry / runtime/*
│   ├── permission/                  # PermissionChecker + rule/*
│   ├── session/                     # SessionStorage / SessionSnapshot
│   ├── task/                        # BackgroundTaskManager / TaskRecord
│   └── coordination/                # AgentOrchestrator / TeamRegistry
│
├── config/
│   ├── Settings.java
│   └── SettingsBootstrap.java
│
├── prompt/
│   ├── SystemPromptBuilder.java
│   ├── ClaudeMdLoader.java
│   └── outputstyles/
│
├── ui/
│   ├── UI.java
│   ├── tui/  # TerminalUI / ConsoleInteractiveSession / AnsiConsole / MarkdownRenderer
│   ├── backend/BackendHost.java
│   └── widgets/  # StatusBar / InputWidget / TranscriptWidget
│
└── util/  # JacksonUtils / UrlSafetyValidator
```

---

## 🔧 内置工具速览（40+）

| 类别 | 工具 |
|------|------|
| **文件** | `read_file` · `write_file` · `edit_file` · `multi_edit` · `undo_edit` · `glob` · `grep` |
| **Shell** | `bash`（持久化 session）· `sleep` |
| **代码智能** | `lsp`（Java/Python 符号 / 定义 / 引用 / 悬停）· `notebook_edit` |
| **网络** | `web_fetch` · `web_search`（DuckDuckGo） |
| **任务** | `todo_write` · `task_create` · `task_get` · `task_list` · `task_stop` · `task_output` · `task_update` |
| **Agent** | `agent_spawn` · `send_message` · `team_create` · `team_delete` · `ask_user_question` |
| **MCP** | `list_mcp_resources` · `read_mcp_resource` · `mcp_auth` · `mcp_tool_adapter`（动态） |
| **Cron** | `cron_create` · `cron_list` · `cron_delete` · `remote_trigger` |
| **模式** | `enter_plan_mode` · `exit_plan_mode` · `enter_worktree` · `exit_worktree` |
| **元工具** | `brief` · `skill` · `tool_search` · `config` |

详见 [`wiki/06-工具系统.md`](wiki/06-工具系统.md)。

---

## ⌨️ 斜杠命令速览（60+）

| 类别 | 命令 |
|------|------|
| **基础** | `/help` · `/exit` · `/clear` · `/status` · `/version` · `/onboarding` · `/feedback` |
| **配置** | `/config` · `/model` · `/permissions` · `/plan` · `/skills` · `/tasks` · `/mcp` · `/login` · `/logout` · `/theme` · `/doctor` · `/effort` · `/passes` · `/fast` · `/plugin` · `/reload-plugins` · `/init` |
| **会话** | `/resume` · `/export` · `/share` · `/session` · `/tag` · `/rewind` · `/copy` · `/compact` · `/context` · `/summary` · `/bridge` |
| **Git** | `/diff` · `/branch` · `/commit` · `/files` · `/issue` · `/pr-comments` |
| **系统** | `/memory` · `/usage` · `/cost` · `/stats` · `/hooks` · `/vim` · `/voice` · `/outputstyle` · `/cd` · `/upgrade` · `/release-notes` · `/privacy` · `/ratelimit` · `/keybindings` |
| **Agent / MCP / Cron** | `/agents` · `/mcp` · `/cron` |

详见 [`wiki/07-斜杠命令系统.md`](wiki/07-斜杠命令系统.md)。

---

## 🔒 权限系统

| 模式 | 行为 |
|------|------|
| `DEFAULT` | 只读自动放行，写入/执行前询问 |
| `FULL_AUTO` | 所有操作放行（黑名单/路径规则依然生效） |
| `PLAN` | 仅允许只读，阻断所有写入操作 |

**评估责任链**（顺序不可重排）：

```
DeniedToolsRule → DeniedCommandsRule → PathRulesRule → ModeDecisionRule
```

即使 `FULL_AUTO` 也不会跳过黑名单 — "不弹确认框" ≠ "放弃安全校验"。详见 [`wiki/08-权限系统.md`](wiki/08-权限系统.md)。

---

## 🔌 插件 & 技能

- **插件目录**：`~/.jharness/plugins/`（用户级） + `<project>/.jharness/plugins/`（项目级）
- **plugin.json** 字段对齐 Claude Code：`name` / `version` / `author` / `homepage` / `repository` / `license` / `keywords` / `skillsDir` / `hooksFile` / `mcpFile` / `commandsDir` / `agentsDir`
- **技能层级**：内置（classpath） → 用户 → 项目
- **技能定义**：Markdown + YAML frontmatter

详见 [`wiki/10-插件与技能系统.md`](wiki/10-插件与技能系统.md)。

---

## 🌐 MCP 协议

内置完整 [Model Context Protocol](https://modelcontextprotocol.io/) 客户端：

- **传输**：stdio（子进程 JSON-RPC） + HTTP/HTTPS
- **动态工具**：MCP 连接完成后由 `ToolRegistry#refreshMcpTools` 自动补注册
- **安全栅栏**：stdio 子进程在 fork 前走 `PermissionGate` 评估

```json
{
  "mcpServers": {
    "filesystem": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem"]
    }
  }
}
```

---

## 🤖 多智能体协调

`AgentOrchestrator` 提供三种执行模式：

- **`executeParallel`** — 并行执行多个独立任务
- **`executeSequential`** — 顺序执行，前序结果作为后续上下文
- **`executeSingle`** — 单任务，结束后发射 `SUBAGENT_STOP` Hook

线程池：命名 `jharness-agent-N`，有界队列 + `CallerRunsPolicy` 回压。详见 [`wiki/13-多智能体协调.md`](wiki/13-多智能体协调.md)。

---

## ⚙️ 配置

### 加载优先级（从低到高，高优先级覆盖低优先级）

```
1. 字段硬编码兜底           (Settings 构造器初始值)
2. classpath defaults/settings.json   (DefaultSettingsLoader，进程内缓存)
3. 用户 ~/.jharness/settings.json
4. 环境变量                 (OPENAI_API_KEY / JHARNESS_MODEL ...)
5. CLI 选项                 (-m / --max-turns / --permission-mode ...)
```

`Settings.load()` 内部按此顺序逐层 `mergeFromJson(JsonNode)` 增量覆盖；仅当节点显式存在且类型正确时才覆盖，避免 Jackson 默认 `0/false` 误覆盖。

### 环境变量

| 变量 | 说明 |
|------|------|
| `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` | API 密钥 |
| `OPENAI_BASE_URL` / `ANTHROPIC_BASE_URL` | API 端点 |
| `OPENAI_MODEL` / `JHARNESS_MODEL` | 默认模型 |
| `JHARNESS_MAX_TOKENS` | 最大输出 token |
| `JHARNESS_HOOK_DEPTH` | Hook 递归深度（内部） |

### 默认数据目录

```
~/.jharness/
├── settings.json         # 全局配置（首次启动从 classpath defaults/ 释放）
├── .onboarded            # Onboarding 完成标记（由 OnboardingMarker 维护）
├── plugins/              # 用户级插件
├── skills/               # 用户级技能
└── data/
    ├── sessions/         # 会话快照
    ├── memories/         # 跨会话记忆
    ├── tasks/            # 后台任务输出
    └── cron_jobs.json    # Cron 定义
```

完整配置项见 [`wiki/16-配置管理.md`](wiki/16-配置管理.md)。

---

## 🛠️ 开发

### 自定义工具

```java
public class MyTool extends BaseTool<MyInput> {
    @Override public String getName() { return "my_tool"; }
    @Override public String getDescription() { return "我的工具"; }
    @Override public Class<MyInput> getInputClass() { return MyInput.class; }
    @Override public boolean isReadOnly(MyInput input) { return true; }
    @Override public CompletableFuture<ToolResult> execute(MyInput input, ToolExecutionContext ctx) {
        return CompletableFuture.completedFuture(ToolResult.success("done"));
    }
}
```

输入 DTO 用 Jakarta Validation 注解（`@NotBlank` / `@NotNull` / `@NotEmpty`）标记必填字段，`BaseTool#toApiSchema` 反射生成 JSON Schema。

### 项目上下文

在项目根创建 `CLAUDE.md`，`McpBootstrap.loadProjectContext` 自动注入到系统提示词。

### 测试

```bash
mvn test                  # 全量测试
mvn spotless:check        # 代码格式检查
mvn spotless:apply        # 自动格式化
```

`pom.xml` 启用 Maven Enforcer（Java 17+ / Maven 3.6.3+ / `dependencyConvergence` / 禁止重复版本声明），ArchUnit 守卫分层依赖方向。

详见 [`wiki/17-扩展开发指南.md`](wiki/17-扩展开发指南.md)。

---

## 📚 文档导航

| 文档 | 主题 |
|------|------|
| [`wiki/01-项目概述.md`](wiki/01-项目概述.md) | 定位、特性、与 OpenHarness 的关系 |
| [`wiki/02-快速开始.md`](wiki/02-快速开始.md) | 环境、构建、模型对接 |
| [`wiki/03-整体架构.md`](wiki/03-整体架构.md) | 分层、SPI、依赖治理 |
| [`wiki/04-核心引擎-QueryEngine.md`](wiki/04-核心引擎-QueryEngine.md) | ReAct、流式、压缩、Hook |
| [`wiki/05-API客户端.md`](wiki/05-API客户端.md) | OpenAI SSE、重试、Provider |
| [`wiki/06-工具系统.md`](wiki/06-工具系统.md) | 40+ 工具、JSON Schema |
| [`wiki/07-斜杠命令系统.md`](wiki/07-斜杠命令系统.md) | 60+ 命令、按键绑定 |
| [`wiki/08-权限系统.md`](wiki/08-权限系统.md) | 模式、责任链、审计 |
| [`wiki/09-会话与记忆.md`](wiki/09-会话与记忆.md) | SessionSnapshot、MemoryManager |
| [`wiki/10-插件与技能系统.md`](wiki/10-插件与技能系统.md) | Plugin / Skill / 信任 / 市场 |
| [`wiki/11-Hook系统.md`](wiki/11-Hook系统.md) | 8 事件 × 5 Runner |
| [`wiki/12-MCP协议客户端.md`](wiki/12-MCP协议客户端.md) | stdio / HTTP、动态工具 |
| [`wiki/13-多智能体协调.md`](wiki/13-多智能体协调.md) | Orchestrator、Team |
| [`wiki/14-后台任务与Cron.md`](wiki/14-后台任务与Cron.md) | BackgroundTaskManager、CronRegistry |
| [`wiki/15-TUI终端界面.md`](wiki/15-TUI终端界面.md) | Lanterna、Console、Widgets |
| [`wiki/16-配置管理.md`](wiki/16-配置管理.md) | Settings 全字段、分层加载、Onboarding |
| [`wiki/17-扩展开发指南.md`](wiki/17-扩展开发指南.md) | 自定义工具 / 命令 / 插件 / Hook / Provider |

---

## 📄 License

本项目采用 [Apache License 2.0](LICENSE) 开源协议。
