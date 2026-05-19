# JHarness 文档索引

JHarness 是一个 Java 实现的轻量级 AI Agent 框架。本 wiki 按"由表及里"的顺序组织：先讲项目定位与上手，再讲架构与核心引擎，最后逐一深入各能力模块与扩展机制。

---

## 📖 阅读路径建议

### 👋 新用户（5 分钟上手）

1. [`01-项目概述.md`](01-项目概述.md) — 它能做什么、与 Claude Code / OpenHarness 的关系
2. [`02-快速开始.md`](02-快速开始.md) — 装环境、起一个对话
3. [`07-斜杠命令系统.md`](07-斜杠命令系统.md) — 学会用 `/help`、`/model`、`/permissions`

### 🧑‍💻 二开 / 集成开发者

1. [`03-整体架构.md`](03-整体架构.md) — 分层、SPI 隔离、依赖方向
2. [`17-扩展开发指南.md`](17-扩展开发指南.md) — 写自定义工具 / 命令 / 插件 / Hook
3. [`16-配置管理.md`](16-配置管理.md) — 配置字段全集、环境变量、数据目录

### 🔬 架构 / 内核研究

1. [`04-核心引擎-QueryEngine.md`](04-核心引擎-QueryEngine.md) — ReAct 循环、流式、压缩、取消
2. [`05-API客户端.md`](05-API客户端.md) — OpenAI SSE 协议实现、重试、错误分类
3. [`08-权限系统.md`](08-权限系统.md) — 责任链、模式决策、审计

---

## 📚 章节清单

| 章节 | 主题 | 关键类 / 文件 |
|------|------|--------------|
| [01-项目概述](01-项目概述.md) | 定位、特性、与 OpenHarness 关系 | `JHarnessApplication` · `pom.xml` |
| [02-快速开始](02-快速开始.md) | 环境、构建、首次启动、Onboarding 向导、模型对接 | `Settings` · `SettingsBootstrap` · `OnboardingService` |
| [03-整体架构](03-整体架构.md) | 分层、SPI、依赖治理、ArchUnit | `kernel.spi.*` · `app.bootstrap.*` |
| [04-核心引擎-QueryEngine](04-核心引擎-QueryEngine.md) | ReAct 循环、流式事件、压缩、取消、持久化 | `QueryEngine` · `ToolCallDispatcher` · `MessageCompactionService` |
| [05-API客户端](05-API客户端.md) | OpenAI 协议 SSE、重试、错误分类、本地端点 | `OpenAiApiClient` · `OpenAiSseStreamReader` · `RetryPolicy` |
| [06-工具系统](06-工具系统.md) | 40+ 内置工具 + JSON Schema 自动生成 | `BaseTool` · `ToolRegistry` · `tools.builtin.*` |
| [07-斜杠命令系统](07-斜杠命令系统.md) | 60+ 命令、注册流程、按键绑定 | `CommandRegistry` · `command.builtin.*` |
| [08-权限系统](08-权限系统.md) | 三种模式、责任链、审计、命令黑名单 | `PermissionChecker` · `capability.permission.rule.*` |
| [09-会话与记忆](09-会话与记忆.md) | SessionSnapshot、自动保存、跨会话记忆 | `SessionStorage` · `MemoryManager` |
| [10-插件与技能系统](10-插件与技能系统.md) | Plugin manifest、技能加载、信任与市场 | `PluginLoader` · `SkillLoader` · `TrustStore` · `MarketplaceRegistry` |
| [11-Hook系统](11-Hook系统.md) | 8 种事件 × 5 种 Runner + 深度防护 | `HookExecutor` · `HookEvent` · `runtime.*HookRunner` |
| [12-MCP协议客户端](12-MCP协议客户端.md) | stdio / HTTP 双传输、动态工具补注册 | `McpClientManager` · `StdioMcpSession` · `HttpMcpSession` |
| [13-多智能体协调](13-多智能体协调.md) | 并行 / 顺序 / 单任务、Team 管理 | `AgentOrchestrator` · `TeamRegistry` |
| [14-后台任务与Cron](14-后台任务与Cron.md) | 后台 Shell/Agent 任务、Cron 定时调度 | `BackgroundTaskManager` · `CronRegistry` |
| [15-TUI终端界面](15-TUI终端界面.md) | Lanterna TUI、ConsoleInteractiveSession、Widgets | `TerminalUI` · `ConsoleInteractiveSession` · `MarkdownRenderer` |
| [16-配置管理](16-配置管理.md) | 分层加载、Settings 全字段、Onboarding 标记、默认模板 | `Settings` · `DefaultSettingsLoader` · `SettingsBootstrap` · `OnboardingMarker` · `defaults/settings.json` |
| [17-扩展开发指南](17-扩展开发指南.md) | 自定义工具 / 命令 / 插件 / Hook / Provider | `BaseTool` · `SlashCommand` · `LlmGateway` |

---

## 🗺️ 模块速查（按代码包）

```
io.leavesfly.jharness
├── app.bootstrap    → 03、04、12         (装配链路)
├── app.cli          → 02、04             (CLI 入口)
├── kernel.engine    → 04                 (核心引擎)
├── kernel.spi       → 03                 (SPI 隔离)
├── kernel.memory    → 09                 (记忆)
├── kernel.edit/plan/state → 04、17       (周边能力)
├── integration.api  → 05                 (LLM API)
├── integration.mcp  → 12                 (MCP)
├── integration.cron → 14                 (Cron)
├── integration.bridge → 07               (/bridge 命令)
├── tools            → 06、17             (工具系统)
├── command          → 07                 (斜杠命令)
├── extension.plugins → 10                (插件)
├── extension.skills  → 10                (技能)
├── capability.hook    → 11               (Hook)
├── capability.permission → 08            (权限)
├── capability.session → 09               (会话)
├── capability.task    → 14               (后台任务)
├── capability.coordination → 13          (Agent 编排)
├── config             → 16               (配置)
├── prompt             → 04、10           (系统提示词)
├── ui                 → 15               (UI)
└── util               → 17               (工具类)
```

---

## 🔗 外部参考

- [Model Context Protocol 规范](https://modelcontextprotocol.io/)
- [Claude Code Plugin Manifest](https://docs.claude.com)（plugin.json 字段对齐参考）
- [OpenAI Chat Completions API](https://platform.openai.com/docs/api-reference/chat)
- [Picocli 文档](https://picocli.info/)
- [Lanterna 终端 UI](https://github.com/mabe02/lanterna)

---

> 📝 反馈：发现文档与代码不符，请在仓库提 Issue 或直接 PR；本套 wiki 与 `src/main/java/io/leavesfly/jharness` 一一对应，重构后由维护者同步更新。
