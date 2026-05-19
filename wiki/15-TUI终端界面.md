# 15 · TUI 终端界面

> 包路径：`io.leavesfly.jharness.ui`
>
> 两套实现：**ANSI 模式（默认）** 与 **Lanterna 全屏模式**。

---

## 1. 概览

JHarness 提供两种交互模式：

| 模式 | 主类 | 说明 |
|------|------|------|
| **ANSI（默认）** | `ConsoleInteractiveSession` | 行式终端，ANSI 颜色，Markdown 渲染 |
| **Lanterna 全屏** | `TerminalUI` | Lanterna TextGraphics，多 widget 布局 |
| **简单 Widget 模型** | `UI` | 包装 widgets，作为 Backend 模式的视图层 |

启动选择由 `JHarnessApplication` 根据 CLI 选项 / `Settings.tuiMode` 决定。

---

## 2. ANSI 模式（`ConsoleInteractiveSession`）

### 2.1 设计

```java
public class ConsoleInteractiveSession {
    private final QueryEngine engine;
    private final CommandRegistry commandRegistry;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final MarkdownRenderer markdownRenderer;
    // ...
}
```

主循环（伪代码）：

```
while (true) {
    print prompt ("> ")
    String input = reader.readLine();

    if (input.startsWith("/")) {
        SlashCommand cmd = commandRegistry.lookup(input);
        CommandResult res = cmd.execute(args, ctx, ec).join();
        if (res.getMessage().equals("exit")) break;
        print res.getMessage();
        continue;
    }

    engine.submitMessage(input, event -> {
        if (event instanceof AssistantTextDelta delta) {
            write(delta.getText());
        } else if (event instanceof ToolExecutionStarted start) {
            write(dim("[使用工具: " + start.getToolName() + "]"));
        }
    }).join();
}
```

### 2.2 ANSI 工具

`AnsiConsole` 提供：

- 16 色前景 + 16 色背景
- 粗体 / 斜体 / 下划线 / 暗色
- 光标控制（保存 / 恢复 / 清屏 / 隐藏）
- `getTerminalWidth()` — 通过 `tput cols` 获取（结果缓存）
- `horizontalRule(width)` — 生成 ─── 分隔线

示例：

```java
import static io.leavesfly.jharness.ui.tui.AnsiConsole.*;

System.out.println(colored("成功", GREEN));
System.out.println(bold("重要"));
System.out.println(horizontalRule(getTerminalWidth()));
```

### 2.3 Markdown 渲染

`MarkdownRenderer`（12 KB）实现简易 Markdown → ANSI 转换：

- 标题 `# / ## / ###` → 加粗 + 颜色 + 分隔线
- 列表 `- / 1.` → `•` / `1.` + 缩进
- 强调 `*text*` / `**text**` → 斜体 / 粗体
- 行内代码 `\`code\`` → 反色背景
- 代码块 ` ```lang ` → 暗色背景，可选语法高亮
- 链接 `[text](url)` → 下划线 + URL 注释

不依赖外部 Markdown 库，避免运行时依赖膨胀。

---

## 3. Lanterna 全屏模式（`TerminalUI`）

### 3.1 设计

```java
public class TerminalUI {
    private final Terminal terminal;          // Lanterna Terminal
    private final Screen screen;              // 双缓冲屏幕
    private final TextGraphics graphics;
    private final QueryEngine engine;
    private final CommandRegistry commandRegistry;
    private final KeybindingResolver keybindingResolver;
    // ...
}
```

支持：

- 全屏 TUI（接管整个终端窗口）
- 多面板布局（状态栏 / 转录区 / 输入区）
- 按键绑定（Ctrl+L 清屏 / Ctrl+K vim / Ctrl+T 任务 等）
- 自适应窗口大小变化（监听 `TerminalResize` 事件）

### 3.2 布局

```
┌─────────────────────────────────────────────┐
│ [Status] Model: gpt-4o  Mode: DEFAULT       │
├─────────────────────────────────────────────┤
│                                             │
│         Transcript (滚动区域)                │
│                                             │
├─────────────────────────────────────────────┤
│ > 输入区                                     │
└─────────────────────────────────────────────┘
```

### 3.3 事件循环

```java
while (running) {
    KeyStroke key = terminal.readInput();
    if (key == null) continue;

    String cmdName = keybindingResolver.resolve(key);
    if (cmdName != null) {
        commandRegistry.lookup("/" + cmdName).execute(...);
        continue;
    }

    inputWidget.handleKey(key);   // 普通字符入框
    if (key.getKeyType() == KeyType.Enter) {
        String text = inputWidget.submit();
        engine.submitMessage(text, this::renderEvent);
    }

    screen.refresh();   // 双缓冲刷新
}
```

### 3.4 按键绑定

由 `command.keybindings` 子包提供（见 [07-斜杠命令系统 § 6](07-斜杠命令系统.md#6-按键绑定)）。

---

## 4. Widget 系统

`ui/widgets/` 提供简单的 Widget 基类与三个内置 Widget：

| Widget | 类 | 用途 |
|--------|----|------|
| `Widget`（基类） | `widgets/Widget.java` | 通用属性（id / visible / size） |
| `StatusBar` | `widgets/StatusBar.java` | 顶部状态栏：模型 / 权限模式 / 状态消息 |
| `TranscriptWidget` | `widgets/TranscriptWidget.java` | 滚动消息区：user / assistant / system |
| `InputWidget` | `widgets/InputWidget.java` | 底部输入框 |

### 4.1 `UI` 协调类

`ui/UI.java`（87 行）把三个 Widget 组合为完整应用：

```java
public class UI {
    private final StatusBar statusBar;
    private final TranscriptWidget transcript;
    private final InputWidget input;
    private boolean running;

    public void start();                            // 显示欢迎信息
    public void stop();
    public String handleInput();                    // 接受用户输入
    public void addAssistantMessage(String);
    public void addSystemMessage(String);
    public void updateStatus(String model, String mode, String msg);
    public void clearHistory();
}
```

作为 Backend 模式（详见 § 5）的视图层。

---

## 5. Backend 模式（`BackendHost`）

`ui/backend/BackendHost.java`（7.5 KB）提供"无 UI"的后台运行模式：

- 不读 stdin，不写 stdout
- 通过编程方式（API / Socket）接收命令
- 适合嵌入到其他应用 / 服务

启动方式：

```bash
java -jar jharness.jar --backend --listen 9999
```

---

## 6. CLI 选项相关

| 选项 | 作用 |
|------|------|
| `--no-tui` | 禁用 Lanterna，强制使用 ANSI 模式 |
| `--tui` | 强制使用 Lanterna 模式 |
| `--backend` | 启动 Backend 模式 |
| `-p / --print <prompt>` | 一次性问答模式（无 REPL） |
| `-c / --continue` | 继续上次会话 |
| `-r / --resume <id>` | 恢复指定会话 |
| `--no-color` | 禁用 ANSI 颜色 |

详见 `app.JHarnessApplication` 的 Picocli `@Option` 定义。

---

## 7. 主题（Theme）

`Settings.theme` 支持：

- `default` — 标准配色
- `dark` — 暗色
- `light` — 亮色
- `monochrome` — 单色（适合不支持彩色的终端）

由 `/theme` 命令运行时切换；同时影响 ANSI 渲染和 Lanterna 渲染。

---

## 8. 流式事件渲染

LLM 流式输出通过 `Consumer<StreamEvent>` 实时刷新：

| 事件 | ANSI 渲染 | Lanterna 渲染 |
|------|----------|--------------|
| `AssistantTextDelta` | `System.out.print(text)` | 累加到 transcript widget，逐字符刷新屏幕 |
| `ToolExecutionStarted` | `print dim("[工具: " + name + "]")` | 状态栏显示当前工具 |
| `ToolExecutionCompleted` | `print dim("[完成: " + (ok ? "OK" : "FAIL") + "]")` | 状态栏恢复 |
| `AssistantTurnComplete` | 打印换行 + token 统计（debug 模式） | 状态栏显示 token 数 |

---

## 9. 输入功能

### 9.1 ANSI 模式（`ConsoleInteractiveSession`）

底层使用 `BufferedReader.readLine()` 逐行读取标准输入：

- 一次读一整行，按 Enter 提交
- 行内不支持光标移动 / 命令历史浏览 / 反向搜索 / Tab 补全（这些依赖原始终端模式，当前版本未实现）
- 流式输出由 `MarkdownRenderer.flushOnLine` 触发：拿到完整行才渲染，避免 Markdown 被截断

### 9.2 Vim / Voice 开关

`Settings.vimEnabled` / `Settings.voiceEnabled` 当前仅作为**状态标志**展示在 `StatusBar`（详见 `StatusBar.render()` 中的 `if (vimEnabled) sb.append(" | VIM")`），并由 `/vim` / `/voice` 命令切换。

具体的 Vim 按键映射 / 语音输入实现尚未在本仓库落地，启用后行为与未启用一致。

### 9.3 Lanterna 模式（`TerminalUI`）

走 Lanterna 的 `Terminal.readInput()` 拿到 `KeyStroke` 后由 `KeybindingResolver.resolve` 翻译为命令名；具体支持的按键以 `~/.jharness/keybindings.json` 与 `DefaultKeybindings.getDefaults()` 为准（详见 [07-斜杠命令系统 § 6](07-斜杠命令系统.md#6-按键绑定)）。

---

## 10. 退出处理

JHarness 通过以下方式退出：

| 触发 | 处理 |
|------|------|
| `/exit` 命令 | `CommandResult.success("exit")` 信号 |
| `Ctrl+C` | 第一次：取消当前查询；连按两次：退出 |
| `Ctrl+D`（EOF） | 退出 |
| 进程信号 | `Runtime.getRuntime().addShutdownHook(...)` 调 `engine.close()` |

退出前会：

1. 触发 `SESSION_END` Hook
2. 关闭 MCP / Cron / Task 资源
3. 取消所有活跃 LLM 请求

---

## 11. 关键类清单

| 类 | 文件 | 行数 | 职责 |
|----|------|------|------|
| `ConsoleInteractiveSession` | `ui/tui/ConsoleInteractiveSession.java` | 18.4 KB | ANSI REPL 主类 |
| `TerminalUI` | `ui/tui/TerminalUI.java` | 17.6 KB | Lanterna 全屏 UI |
| `AnsiConsole` | `ui/tui/AnsiConsole.java` | 139 | ANSI 颜色 / 光标 / 宽度工具 |
| `MarkdownRenderer` | `ui/tui/MarkdownRenderer.java` | 12 KB | Markdown → ANSI |
| `UI` | `ui/UI.java` | 87 | 简单 Widget 协调类 |
| `StatusBar` | `ui/widgets/StatusBar.java` | 1.7 KB | 顶部状态栏 |
| `TranscriptWidget` | `ui/widgets/TranscriptWidget.java` | 2.9 KB | 滚动消息区 |
| `InputWidget` | `ui/widgets/InputWidget.java` | 1.2 KB | 输入框 |
| `Widget` | `ui/widgets/Widget.java` | 491 B | Widget 基类 |
| `BackendHost` | `ui/backend/BackendHost.java` | 7.5 KB | Backend 模式 |

---

## 12. 下一步

- 命令系统与按键绑定 → [07-斜杠命令系统](07-斜杠命令系统.md)
- 流式事件协议 → [04-核心引擎 § 4](04-核心引擎-QueryEngine.md#4-流式事件协议)
- 配置项（theme / vimMode 等） → [16-配置管理](16-配置管理.md)
