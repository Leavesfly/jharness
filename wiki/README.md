# JHarness 技术 Wiki

> JHarness 是一个用 Java 17 实现的轻量级 AI Agent 框架，是 OpenHarness 项目的 Java 端口。本 Wiki 为项目提供系统化的技术参考文档。

## 📚 文档导航

### 一、入门与总览

| 文档 | 说明 |
|------|------|
| [01-项目概述](01-项目概述.md) | 项目定位、核心特性、能力清单、代码规模 |
| [02-快速开始](02-快速开始.md) | 环境准备、构建打包、首次运行、常见用法 |
| [03-整体架构](03-整体架构.md) | 分层架构、核心执行流、模块依赖关系 |

### 二、核心引擎

| 文档 | 说明 |
|------|------|
| [04-核心引擎 QueryEngine](04-核心引擎-QueryEngine.md) | ReAct 循环、流式事件、消息管理、消息压缩、成本追踪 |
| [05-API 客户端](05-API客户端.md) | OpenAI 兼容协议、SSE 流式、重试策略、错误体系 |

### 三、工具与命令

| 文档 | 说明 |
|------|------|
| [06-工具系统](06-工具系统.md) | `BaseTool`、`ToolRegistry`、40+ 内置工具分类详解 |
| [07-斜杠命令系统](07-斜杠命令系统.md) | `CommandRegistry`、50+ 命令、处理器分类、快捷键 |

### 四、安全与持久化

| 文档 | 说明 |
|------|------|
| [08-权限系统](08-权限系统.md) | 三级模式、工具白/黑名单、路径规则、命令黑名单、审计日志 |
| [09-会话与记忆](09-会话与记忆.md) | `SessionStorage`、`MemoryManager`、`BridgeSessionManager` |

### 五、扩展机制

| 文档 | 说明 |
|------|------|
| [10-插件与技能系统](10-插件与技能系统.md) | Plugin 清单、用户/项目级加载、Skill Markdown + YAML |
| [11-Hook 系统](11-Hook系统.md) | 生命周期事件、Command/HTTP/Prompt/Agent 四类 Hook |
| [12-MCP 协议客户端](12-MCP协议客户端.md) | stdio/HTTP 传输、工具发现、资源访问、SSRF 防护 |
| [13-多智能体协调](13-多智能体协调.md) | `AgentOrchestrator`、并行/顺序执行、`TeamRegistry` |

### 六、运行时与基础设施

| 文档 | 说明 |
|------|------|
| [14-后台任务与 Cron](14-后台任务与Cron.md) | `BackgroundTaskManager`、`CronRegistry` 定时调度 |
| [15-TUI 终端界面](15-TUI终端界面.md) | Lanterna、`TerminalUI`、`ConsoleInteractiveSession`、Widgets |
| [16-配置管理](16-配置管理.md) | `Settings`、环境变量、多层覆盖、数据目录结构 |

### 七、面向开发者

| 文档 | 说明 |
|------|------|
| [17-扩展开发指南](17-扩展开发指南.md) | 自定义工具/命令/插件/技能/Hook 的开发流程 |

---

## 🗺️ 模块地图

```
io.leavesfly.jharness
├── JHarnessApplication          # CLI 入口 (Picocli)
├── core/                        # 核心运行时
│   ├── engine/                  # QueryEngine + CostTracker + 消息压缩
│   ├── edit/                    # 文件编辑历史 (DiffUtils)
│   ├── plan/                    # 计划模式执行计划
│   ├── state/                   # 应用状态
│   ├── Settings                 # 全局配置
│   └── MemoryManager            # 跨会话记忆
├── integration/                 # 外部系统集成
│   ├── api/                     # LLM API (OpenAI 兼容)
│   ├── mcp/                     # MCP 协议客户端
│   └── CronRegistry             # Cron 定时任务
├── tools/                       # 40+ 内置工具
├── command/                     # 斜杠命令 & 快捷键
│   ├── commands/handlers/       # 命令处理器 (20+ 类)
│   └── keybindings/
├── agent/                       # 智能体运行层
│   ├── hooks/                   # Hook 生命周期（HookDefinition / HookExecutor / HookRegistry）
│   ├── tasks/                   # 后台任务（BackgroundTaskManager + TaskRecord + TaskStatus）
│   └── coordinator/             # 多智能体编排（AgentOrchestrator + TeamRegistry + TeamRecord）
├── session/                     # 会话与安全
│   ├── permissions/             # 权限系统（PermissionChecker 等）
│   ├── sessions/                # 会话持久化（SessionStorage + SessionSnapshot）
│   └── bridge/                  # 桥接会话（BridgeSessionManager + WorkSecretHelper）
├── extension/                   # 扩展机制
│   ├── plugins/                 # PluginLoader + PluginInstaller + PluginManifest
│   └── skills/                  # SkillLoader + SkillRegistry + SkillDefinition
├── prompts/                     # 系统提示词 & 输出样式
│                                # SystemPromptBuilder / ClaudeMdLoader / OutputStyleLoader
├── ui/                          # 终端 UI
│   ├── UI.java                  # 入口协调器
│   ├── tui/                     # TerminalUI（Lanterna）+ ConsoleInteractiveSession
│   │                            # + MarkdownRenderer + AnsiConsole
│   ├── backend/                 # BackendHost（JSON Lines 协议）
│   └── widgets/                 # StatusBar / TranscriptWidget / InputWidget
└── util/                        # JacksonUtils / UrlSafetyValidator
```

---

## 🧭 阅读建议

- **首次接触项目**：先读 [01](01-项目概述.md) → [02](02-快速开始.md) → [03](03-整体架构.md)，30 分钟内建立整体认识。
- **开发者想扩展工具**：直接跳到 [06](06-工具系统.md) → [17](17-扩展开发指南.md)。
- **关注安全模型**：重点阅读 [08 权限](08-权限系统.md) 与 [11 Hook](11-Hook系统.md)。
- **关注运行时机制**：聚焦 [04 核心引擎](04-核心引擎-QueryEngine.md) 与 [05 API](05-API客户端.md)。
- **二次集成外部系统**：阅读 [12 MCP](12-MCP协议客户端.md) 与 [13 多智能体](13-多智能体协调.md)。

---

> 本 Wiki 基于源码版本 `0.1.0-SNAPSHOT` 编写，若源码结构发生重大调整请同步更新。
