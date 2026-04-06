# JHarness

**Java 实现的轻量级 AI 智能体框架** — 一个功能完备的 AI Agent 开发与运行平台，提供丰富的内置工具、插件系统、多智能体协调、MCP 协议支持和终端交互界面。

[![Java](https://img.shields.io/badge/Java-17+-blue.svg)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/Maven-3.x-orange.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

---

## 📖 目录

- [项目简介](#-项目简介)
- [核心特性](#-核心特性)
- [快速开始](#-快速开始)
- [架构概览](#-架构概览)
- [模块详解](#-模块详解)
- [内置工具](#-内置工具)
- [斜杠命令](#-斜杠命令)
- [插件系统](#-插件系统)
- [技能系统](#-技能系统)
- [Hook 系统](#-hook-系统)
- [MCP 协议支持](#-mcp-协议支持)
- [多智能体协调](#-多智能体协调)
- [权限系统](#-权限系统)
- [配置管理](#-配置管理)
- [开发指南](#-开发指南)
- [技术栈](#-技术栈)

---

## 🌟 项目简介

JHarness 是 OpenHarness 的 Java 实现，旨在提供一个**轻量级、可扩展、功能完备**的 AI 智能体框架。它将 LLM 的推理能力与丰富的工具生态相结合，使 AI Agent 能够执行文件操作、代码分析、Shell 命令、网络搜索等多种任务。

JHarness 支持**交互式终端界面（TUI）**和**单次查询模式**，适用于开发辅助、自动化任务、代码审查等多种场景。

---

## ✨ 核心特性

- **🔧 40+ 内置工具** — 文件读写、代码搜索、Shell 执行、Web 搜索、LSP 代码智能等
- **🔌 插件系统** — 支持从用户目录和项目目录加载自定义插件
- **🎯 技能系统** — 通过 Markdown 文件定义可复用的 AI 技能
- **🔗 Hook 系统** — 在会话和工具执行的关键生命周期点注入自定义逻辑
- **🌐 MCP 协议** — 完整的 Model Context Protocol 客户端支持（stdio / HTTP）
- **🤖 多智能体协调** — 支持多 Agent 并行/顺序执行和团队管理
- **🔒 权限系统** — 三级权限模式（Default / Full Auto / Plan），细粒度控制工具执行
- **💬 会话管理** — 会话持久化、恢复、导出，支持消息压缩
- **🖥️ 终端 UI** — 基于 Lanterna 的交互式终端界面
- **📊 成本追踪** — 实时跟踪 Token 使用量和 API 调用成本
- **⏰ 定时任务** — 内置 Cron 作业注册和管理
- **🔄 流式响应** — 基于 SSE 的实时流式输出

---

## 🚀 快速开始

### 环境要求

- **Java 17** 或更高版本
- **Maven 3.x**
- Anthropic API Key（或兼容端点）

### 构建项目

```bash
# 克隆仓库
git clone <repo-url>
cd jharness

# 编译打包（生成包含所有依赖的 fat jar）
mvn clean package -DskipTests

# 生成的 jar 位于 target/jharness-0.1.0-SNAPSHOT.jar
```

### 配置 API Key

```bash
# 通过环境变量配置
export ANTHROPIC_API_KEY="your-api-key"

# 或通过配置文件（~/.jharness/settings.json）
```

### 运行

```bash
# 交互式模式
java -jar target/jharness-0.1.0-SNAPSHOT.jar

# 启用 TUI 界面
java -jar target/jharness-0.1.0-SNAPSHOT.jar --tui

# 单次查询模式
java -jar target/jharness-0.1.0-SNAPSHOT.jar -p "解释这段代码的功能"

# 指定模型
java -jar target/jharness-0.1.0-SNAPSHOT.jar -m claude-3-5-sonnet

# 全自动权限模式
java -jar target/jharness-0.1.0-SNAPSHOT.jar --permission-mode full_auto

# 调试模式
java -jar target/jharness-0.1.0-SNAPSHOT.jar -d

# 继续上一次会话
java -jar target/jharness-0.1.0-SNAPSHOT.jar -c

# 恢复指定会话
java -jar target/jharness-0.1.0-SNAPSHOT.jar -r <session-id>
```

### 命令行参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-p, --print` | 单次查询模式，直接执行提示并输出结果 | - |
| `-m, --model` | 指定使用的 LLM 模型 | 默认模型 |
| `-d, --debug` | 启用调试模式 | `false` |
| `-c, --continue` | 继续上一次会话 | `false` |
| `-r, --resume` | 恢复指定会话 ID | - |
| `--permission-mode` | 权限模式：`default`, `full_auto`, `plan` | `default` |
| `--max-turns` | 最大对话轮次 | `8` |
| `--output-format` | 输出格式：`text`, `json`, `stream-json` | `text` |
| `--tui` | 启用终端用户界面 | `false` |

---

## 🏗️ 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                     JHarnessApplication                     │
│                    (Picocli CLI 入口)                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │ TUI 界面  │  │ UI 组件  │  │ 状态管理  │  │  快捷键绑定 │  │
│  │(Lanterna) │  │(Widgets) │  │(AppState)│  │(Keybinding)│  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────────────┘  │
│       │              │             │                         │
│  ┌────▼──────────────▼─────────────▼──────────────────────┐  │
│  │                   QueryEngine                          │  │
│  │          (核心查询循环 / ReAct Loop)                     │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐  │  │
│  │  │ LLM API 调用 │  │  工具执行引擎  │  │  消息压缩服务 │  │  │
│  │  └──────┬──────┘  └──────┬───────┘  └──────────────┘  │  │
│  └─────────┼────────────────┼────────────────────────────┘  │
│            │                │                                │
│  ┌─────────▼──────┐  ┌─────▼────────────────────────────┐   │
│  │ AnthropicAPI   │  │         ToolRegistry              │   │
│  │ Client (SSE)   │  │  ┌────┐┌────┐┌────┐┌────┐┌────┐  │   │
│  │ + RetryPolicy  │  │  │Bash││File││Grep││Web ││LSP │  │   │
│  └────────────────┘  │  │Tool││Read││Tool││Srch││Tool│  │   │
│                      │  └────┘└────┘└────┘└────┘└────┘  │   │
│  ┌────────────────┐  │  + 35 more tools...               │   │
│  │PermissionCheck │  └──────────────────────────────────┘   │
│  │ er (权限检查)   │                                         │
│  └────────────────┘  ┌──────────────────────────────────┐   │
│                      │        扩展系统                     │   │
│  ┌────────────────┐  │  ┌──────┐┌──────┐┌─────┐┌─────┐  │   │
│  │ SessionStorage │  │  │Plugin││Skill ││Hook ││ MCP │  │   │
│  │ (会话持久化)    │  │  │System││System││Sys. ││Clnt │  │   │
│  └────────────────┘  │  └──────┘└──────┘└─────┘└─────┘  │   │
│                      └──────────────────────────────────┘   │
│  ┌────────────────┐  ┌──────────────────────────────────┐   │
│  │  CostTracker   │  │     AgentOrchestrator             │   │
│  │  (成本追踪)     │  │     (多智能体协调)                  │   │
│  └────────────────┘  └──────────────────────────────────┘   │
│                                                             │
│  ┌────────────────┐  ┌──────────────────────────────────┐   │
│  │ MemoryManager  │  │  BackgroundTaskManager / Cron     │   │
│  │ (记忆管理)      │  │  (后台任务 / 定时任务)              │   │
│  └────────────────┘  └──────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 核心执行流程

```
用户输入 → QueryEngine.submitMessage()
         → LLM API 调用 (SSE 流式)
         → 解析响应 (文本 / 工具调用)
         → 权限检查 (PermissionChecker)
         → 工具执行 (并行 / 顺序)
         → 结果反馈给 LLM
         → 循环直到完成或达到最大轮次
```

---

## 📦 模块详解

### 项目结构

```
src/main/java/io/leavesfly/jharness/
├── JHarnessApplication.java    # 应用主入口 (Picocli CLI)
├── api/                        # LLM API 客户端
│   ├── AnthropicApiClient.java # Anthropic API 实现 (SSE 流式)
│   ├── retry/                  # 指数退避重试策略
│   └── errors/                 # API 异常体系
├── engine/                     # 核心查询引擎
│   ├── QueryEngine.java        # ReAct 查询循环
│   ├── CostTracker.java        # Token 成本追踪
│   ├── MessageCompactionService.java  # 消息压缩
│   ├── model/                  # 数据模型 (消息、内容块)
│   └── stream/                 # 流式事件 (文本增量、工具执行)
├── tools/                      # 工具系统 (40+ 内置工具)
│   ├── BaseTool.java           # 工具抽象基类
│   ├── ToolRegistry.java       # 工具注册表
│   └── input/                  # 工具输入模型
├── commands/                   # 斜杠命令系统
│   ├── CommandRegistry.java    # 命令注册表 (50+ 命令)
│   └── handlers/               # 命令处理器
├── plugins/                    # 插件系统
├── skills/                     # 技能系统
├── hooks/                      # Hook 生命周期系统
├── mcp/                        # MCP 协议客户端
├── coordinator/                # 多智能体协调
├── permissions/                # 权限系统
├── sessions/                   # 会话持久化
├── memory/                     # 跨会话记忆管理
├── tasks/                      # 后台任务管理
├── services/                   # Cron 定时任务
├── config/                     # 配置管理
├── state/                      # 应用状态管理
├── prompts/                    # 系统提示词构建
├── bridge/                     # 桥接会话管理
├── tui/                        # 终端 UI (Lanterna)
├── ui/                         # UI 组件系统
├── keybindings/                # 快捷键绑定
├── outputstyles/               # 输出样式
└── util/                       # 工具类
```

### 核心模块说明

| 模块 | 说明 |
|------|------|
| **engine** | 核心查询引擎，实现 ReAct 循环（LLM 调用 → 工具执行 → 结果反馈），支持多工具并行执行 |
| **api** | Anthropic API 客户端，基于 OkHttp + SSE 实现流式响应，内置指数退避重试策略 |
| **tools** | 工具系统，提供 40+ 内置工具，支持异步执行和自动 JSON Schema 生成 |
| **commands** | 斜杠命令系统，提供 50+ 交互式命令（`/help`, `/config`, `/git` 等） |
| **plugins** | 插件系统，支持从 `~/.jharness/plugins/` 和项目目录加载插件 |
| **skills** | 技能系统，通过 Markdown + YAML frontmatter 定义可复用 AI 技能 |
| **hooks** | Hook 系统，在 `SESSION_START/END`、`PRE/POST_TOOL_USE` 等事件点注入逻辑 |
| **mcp** | MCP 协议客户端，支持 stdio 和 HTTP 传输，动态发现和注册 MCP 工具 |
| **coordinator** | 多智能体协调器，支持并行/顺序执行多个 Agent 任务和团队管理 |
| **permissions** | 权限系统，三级模式 + 工具白/黑名单 + 路径规则 + 命令黑名单 |
| **memory** | 跨会话记忆管理，支持分类、搜索和自动清理 |
| **sessions** | 会话快照持久化，JSON 格式存储，支持列表和恢复 |

---

## 🔧 内置工具

JHarness 提供 **40+ 内置工具**，覆盖文件操作、代码智能、系统命令、网络搜索等多个领域：

### 文件操作

| 工具 | 说明 |
|------|------|
| `read_file` | 读取文件内容，支持按行范围读取 |
| `write_file` | 写入文件内容 |
| `edit_file` | 在文件中查找并替换文本 |
| `glob` | 使用 glob 模式查找文件（如 `**/*.java`） |
| `grep` | 在文件内容中搜索文本，支持正则表达式 |

### 代码智能

| 工具 | 说明 |
|------|------|
| `lsp` | LSP 代码智能：符号提取、定义跳转、引用查找、悬停信息（支持 Java / Python） |
| `notebook_edit` | 创建或编辑 Jupyter Notebook 单元格 |

### 系统命令

| 工具 | 说明 |
|------|------|
| `bash` | 执行 Shell 命令并返回输出，支持超时控制 |
| `sleep` | 延迟执行 |

### 网络工具

| 工具 | 说明 |
|------|------|
| `web_search` | 网络搜索（基于 DuckDuckGo） |
| `web_fetch` | 获取网页内容 |

### 任务管理

| 工具 | 说明 |
|------|------|
| `todo_write` | 创建和管理任务列表 |
| `task_create` | 创建后台 Shell 任务 |
| `task_get` / `task_list` | 查询任务状态 |
| `task_stop` | 停止后台任务 |
| `task_output` / `task_update` | 获取任务输出 / 更新任务 |

### Agent 与团队

| 工具 | 说明 |
|------|------|
| `agent_spawn` | 生成子 Agent 处理复杂任务 |
| `send_message` | 向 Agent 发送消息 |
| `team_create` / `team_delete` | 创建 / 删除多智能体团队 |
| `ask_user_question` | 向用户提问 |

### MCP 工具

| 工具 | 说明 |
|------|------|
| `list_mcp_resources` | 列出 MCP 资源 |
| `read_mcp_resource` | 读取 MCP 资源 |
| `mcp_auth` | MCP 认证 |
| `mcp_tool_adapter` | 动态 MCP 工具适配器 |

### 定时任务

| 工具 | 说明 |
|------|------|
| `cron_create` | 创建 Cron 定时任务 |
| `cron_list` / `cron_delete` | 列出 / 删除 Cron 任务 |
| `remote_trigger` | 远程触发 Cron 任务 |

### 其他

| 工具 | 说明 |
|------|------|
| `config` | 运行时配置管理 |
| `brief` | 生成内容摘要 |
| `tool_search` | 搜索可用工具 |
| `enter_plan_mode` / `exit_plan_mode` | 进入 / 退出计划模式 |
| `enter_worktree` / `exit_worktree` | 进入 / 退出 worktree 模式 |
| `skill` | 执行预定义技能 |

---

## ⌨️ 斜杠命令

在交互式模式下，输入 `/` 开头的命令进行快捷操作：

### 基础命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示可用命令列表 |
| `/exit` | 退出程序 |
| `/clear` | 清空对话历史 |
| `/status` | 查看会话状态 |
| `/version` | 查看版本信息 |

### 配置命令

| 命令 | 说明 |
|------|------|
| `/config` | 配置管理 |
| `/model` | 切换 LLM 模型 |
| `/permissions` | 权限模式配置 |
| `/theme` | 主题设置 |
| `/keybindings` | 快捷键绑定 |

### 会话命令

| 命令 | 说明 |
|------|------|
| `/resume` | 恢复历史会话 |
| `/export` | 导出会话记录 |
| `/share` | 分享会话 |
| `/compact` | 压缩会话消息 |
| `/rewind` | 回滚会话 |
| `/context` | 上下文管理 |

### Git 命令

| 命令 | 说明 |
|------|------|
| `/diff` | 查看 Git diff |
| `/branch` | Git 分支管理 |
| `/commit` | Git 提交 |
| `/files` | Git 文件状态 |

### 系统命令

| 命令 | 说明 |
|------|------|
| `/memory` | 记忆管理 |
| `/usage` / `/cost` | 使用量 / 成本统计 |
| `/hooks` | Hook 管理 |
| `/mcp` | MCP 服务管理 |
| `/cron` | Cron 任务管理 |
| `/plugin` | 插件管理 |
| `/doctor` | 系统诊断 |
| `/bridge` | 桥接配置 |

---

## 🔌 插件系统

JHarness 支持通过插件扩展功能，插件可以包含自定义技能、Hook 和 MCP 配置。

### 插件目录

- **用户级插件**：`~/.jharness/plugins/`
- **项目级插件**：`<project-dir>/.jharness/plugins/`

### 插件结构

```
my-plugin/
├── plugin.json              # 插件清单文件
├── skills/                  # 自定义技能目录
│   └── my-skill.md
├── hooks.json               # Hook 定义
└── mcp.json                 # MCP 服务配置
```

### 插件清单 (`plugin.json`)

```json
{
  "name": "my-plugin",
  "version": "1.0.0",
  "description": "我的自定义插件",
  "enabledByDefault": true,
  "skillsDir": "skills",
  "hooksFile": "hooks.json",
  "mcpFile": "mcp.json"
}
```

---

## 🎯 技能系统

技能是预定义的 AI 行为模板，通过 Markdown 文件定义。

### 技能加载层级

1. **内置技能** — classpath resources 中的预置技能
2. **用户技能** — `~/.jharness/skills/*.md`
3. **项目技能** — `<project-dir>/.jharness/skills/*.md`

### 技能定义格式

```markdown
---
name: code-review
description: 执行代码审查并提供改进建议
---

你是一个代码审查专家。请对提供的代码进行以下方面的审查：

1. 代码质量和可读性
2. 潜在的 Bug 和安全问题
3. 性能优化建议
4. 最佳实践遵循情况
```

---

## 🔗 Hook 系统

Hook 允许在 Agent 执行的关键生命周期点注入自定义逻辑。

### 支持的事件

| 事件 | 触发时机 |
|------|----------|
| `SESSION_START` | 会话开始时 |
| `SESSION_END` | 会话结束时 |
| `PRE_TOOL_USE` | 工具执行前 |
| `POST_TOOL_USE` | 工具执行后 |

### Hook 类型

| 类型 | 说明 |
|------|------|
| **Command** | 执行 Shell 命令，支持超时和环境变量注入 |
| **HTTP** | 发送 POST 请求到指定 URL，支持自定义 Headers |
| **Prompt** | LLM 验证（通过提示词进行检查） |
| **Agent** | 深度 Agent 验证 |

### Hook 配置示例

```json
{
  "hooks": [
    {
      "event": "PRE_TOOL_USE",
      "type": "command",
      "pattern": "bash",
      "command": "echo 'About to execute: $ARGUMENTS'",
      "timeout": 10
    }
  ]
}
```

---

## 🌐 MCP 协议支持

JHarness 内置完整的 [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) 客户端，支持连接外部 MCP 服务器以扩展工具和资源。

### 支持的传输方式

- **stdio** — 通过子进程标准输入/输出通信（JSON-RPC）
- **HTTP/HTTPS** — 通过 HTTP 请求通信

### 配置 MCP 服务器

在 `~/.jharness/settings.json` 或项目配置中添加：

```json
{
  "mcpServers": {
    "my-server": {
      "transport": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem"],
      "env": {}
    }
  }
}
```

MCP 服务器提供的工具会被自动发现并注册到 `ToolRegistry`，可直接被 Agent 调用。

---

## 🤖 多智能体协调

JHarness 支持多个 Agent 协同工作，适用于复杂任务的分解和并行处理。

### 执行模式

- **并行执行** (`executeParallel`) — 多个 Agent 同时执行独立任务
- **顺序执行** (`executeSequential`) — Agent 按顺序执行，前序结果作为上下文传递
- **单任务执行** (`executeSingle`) — 执行单个 Agent 任务

### 团队管理

通过 `TeamRegistry` 管理多智能体团队：

- 创建团队并分配 Agent 角色
- 动态添加/移除团队成员
- 团队级别的任务分发

---

## 🔒 权限系统

JHarness 提供多层权限控制机制，确保 Agent 操作的安全性。

### 权限模式

| 模式 | 说明 |
|------|------|
| **Default** | 默认模式：只读操作自动允许，写入/执行操作需要用户确认 |
| **Full Auto** | 全自动模式：允许所有操作，无需确认 |
| **Plan** | 计划模式：仅允许只读操作，阻止所有写入操作 |

### 权限检查流程

```
工具调用 → 白名单检查 → 黑名单检查 → 路径规则检查 → 命令黑名单检查 → 模式决策
```

### 权限决策结果

- **允许** — 直接执行
- **拒绝** — 阻止执行并返回原因
- **需要确认** — 等待用户确认后执行

---

## ⚙️ 配置管理

### 配置文件

配置文件位于 `~/.jharness/settings.json`，支持多层配置覆盖：

```
默认值 → 环境变量 → 配置文件
```

### 环境变量

| 环境变量 | 说明 |
|----------|------|
| `ANTHROPIC_API_KEY` | Anthropic API 密钥 |
| `JHARNESS_MODEL` | 默认使用的模型 |

### 配置项

配置涵盖以下维度：

- **模型配置** — 模型名称、API 端点、Provider
- **权限配置** — 权限模式、工具白/黑名单、路径规则
- **UI 配置** — 主题、Vim 模式、语音模式
- **MCP 配置** — MCP 服务器连接配置
- **插件配置** — 插件启用/禁用

### 数据目录

```
~/.jharness/
├── settings.json          # 全局配置
├── plugins/               # 用户级插件
├── skills/                # 用户级技能
├── sessions/              # 会话存储
├── memories/              # 记忆存储
└── data/
    └── cron_jobs.json     # Cron 任务定义
```

---

## 🛠️ 开发指南

### 自定义工具

继承 `BaseTool<T>` 抽象类创建自定义工具：

```java
public class MyTool extends BaseTool<MyToolInput> {

    @Override
    public String getName() {
        return "my_tool";
    }

    @Override
    public String getDescription() {
        return "我的自定义工具";
    }

    @Override
    public Class<MyToolInput> getInputClass() {
        return MyToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(
            MyToolInput input, ToolExecutionContext context) {
        // 实现工具逻辑
        return CompletableFuture.completedFuture(
            ToolResult.success("执行成功"));
    }

    @Override
    public boolean isReadOnly(MyToolInput input) {
        return true; // 只读工具无需权限确认
    }
}
```

### 注册工具

```java
ToolRegistry registry = ToolRegistry.withDefaults();
registry.register(new MyTool());
```

### 项目上下文文件

在项目根目录创建 `CLAUDE.md` 文件，JHarness 会自动加载并注入到系统提示词中：

```markdown
# 项目说明

这是一个 Spring Boot 微服务项目...

## 编码规范

- 使用 Google Java Style
- 所有 API 需要编写单元测试
```

### 运行测试

```bash
# 运行所有测试
mvn test

# 运行特定测试类
mvn test -Dtest=JHarnessComprehensiveTest

# 代码格式化检查
mvn spotless:check

# 代码格式化
mvn spotless:apply
```

---

## 📚 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| **Java** | 17+ | 运行时环境 |
| **Picocli** | 4.7.5 | CLI 框架 |
| **OkHttp** | 4.12.0 | HTTP 客户端 / SSE 支持 |
| **Jackson** | 2.17.0 | JSON / YAML 序列化 |
| **Lanterna** | 3.1.1 | 终端 UI 框架 |
| **Jsoup** | 1.17.2 | HTML 解析（Web 搜索） |
| **SnakeYAML** | 2.2 | YAML 解析 |
| **SLF4J + Logback** | 2.0.12 / 1.5.3 | 日志框架 |
| **Hibernate Validator** | 8.0.1.Final | Bean Validation |
| **JUnit 5** | 5.10.2 | 单元测试 |
| **Mockito** | 5.11.0 | Mock 测试 |

---

## 📄 License

本项目采用 [Apache License 2.0](LICENSE) 开源协议。
