# 15 - TUI 终端界面

> 位于 `io.leavesfly.jharness.ui`。UI 层分为三个子包：`ui/tui/`（Lanterna 全屏 TUI 与 ANSI 控制台）、`ui/widgets/`（可组合组件）、`ui/backend/`（前后端分离协议）。核心 Agent 逻辑作为后端，通过 JSON 事件协议与前端通信。

**关键类**：
- `ui/UI.java` — UI 入口协调器（组合 widgets）
- `ui/tui/TerminalUI.java` — Lanterna 全屏 TUI 驱动
- `ui/tui/ConsoleInteractiveSession.java` — ANSI 行模式交互会话（headless 友好）
- `ui/tui/MarkdownRenderer.java` — 终端内 Markdown 渲染
- `ui/tui/AnsiConsole.java` — ANSI 转义码辅助
- `ui/backend/BackendHost.java` — 前后端 JSON Lines 协议模型

## 1. 分层架构

```
┌─────────────────────────────────────────┐
│         UI（前端，Lanterna）            │
│   ┌─────────────────────────────────┐   │
│   │  StatusBar                      │   │  顶栏：模型 / 权限 / 状态
│   ├─────────────────────────────────┤   │
│   │                                 │   │
│   │  TranscriptWidget               │   │  主区：对话历史
│   │                                 │   │
│   ├─────────────────────────────────┤   │
│   │  InputWidget                    │   │  底部：输入框
│   └─────────────────────────────────┘   │
└────────────────┬────────────────────────┘
                 │ JSON Lines 协议
                 │ (FrontendRequest / BackendEvent)
┌────────────────┴────────────────────────┐
│       BackendHost（后端驱动）           │
│        QueryEngine + Tools              │
└─────────────────────────────────────────┘
```

## 2. `UI`：入口协调器

`UI` 类整合三个子组件：

```java
public class UI {
    private final StatusBar statusBar;
    private final TranscriptWidget transcript;
    private final InputWidget input;

    public void render(Screen screen);                // 由主循环调用
    public void onKeyStroke(KeyStroke key);           // 路由按键
    public void onBackendEvent(BackendEvent event);   // 后端事件分发
}
```

渲染循环由 `TerminalUI`（Lanterna 全屏模式）或 `ConsoleInteractiveSession`（行模式）驱动：每帧调用 `render(screen)` 或每次行输入后重绘。

## 3. `Widget`：组件基类

```java
public abstract class Widget {
    public abstract void render(Screen screen, int x, int y, int width, int height);
}
```

所有组件统一 `render(screen, x, y, w, h)` 接口，由 `UI` 根据当前终端尺寸做 **box layout** 分配坐标。

## 4. `StatusBar`：状态栏

显示当前会话关键状态：

| 字段 | 来源 |
|------|------|
| `model` | `AppState.model` |
| `permissionMode` | `AppState.permissionMode` |
| `statusMessage` | 临时提示（工具执行中、错误等） |
| `vimEnabled` | `AppState.vimEnabled` |
| `voiceEnabled` | `AppState.voiceEnabled` |

会通过 `AppStateStore.subscribe(listener)` 订阅状态变更，变化时立即刷新：

```java
appStateStore.subscribe((oldS, newS) -> {
    statusBar.setModel(newS.getModel());
    statusBar.setPermissionMode(newS.getPermissionMode());
    // ...
    ui.requestRepaint();
});
```

## 5. `TranscriptWidget`：对话历史

### 5.1 数据模型

```java
class TranscriptItem {
    Role role;           // USER / ASSISTANT / TOOL / SYSTEM
    String content;
    Instant timestamp;
}

enum Role { USER, ASSISTANT, TOOL, SYSTEM }
```

组件内部持有 `List<TranscriptItem>`，默认上限 `maxItems`（可配置，超过则丢弃最早条目）。

### 5.2 渲染

- 按 `Role` 使用不同颜色前缀：
  - USER：`› ` 蓝色
  - ASSISTANT：`● ` 绿色
  - TOOL：`▸ ` 黄色
  - SYSTEM：`※ ` 灰色
- 长文本自动按终端宽度折行
- 支持 **流式追加**（`appendToLastAssistant(delta)`）：LLM 流式响应时无需新增条目

### 5.3 事件驱动

接收 `BackendEvent` 三种类型：
- `TRANSCRIPT_ITEM`：新增一条
- `ASSISTANT_DELTA`：追加到最后一条 assistant
- `ASSISTANT_COMPLETE` / `LINE_COMPLETE`：标记当前响应完整
- `CLEAR_TRANSCRIPT`：清空

## 6. `InputWidget`：输入框

### 6.1 结构

```java
public class InputWidget extends Widget {
    private final StringBuilder content;      // 当前输入
    private final String prompt;              // 前缀，如 "> "
    private final int maxLength;
    private int cursorPos;
}
```

### 6.2 核心操作

```java
input.appendChar(c);          // 插入字符（含光标更新）
input.backspace();            // 删
input.moveCursorLeft();  / moveCursorRight();
input.moveCursorHome();  / moveCursorEnd();
String line = input.submit(); // 取走内容并清空
```

### 6.3 按键路由

`UI.onKeyStroke(key)` 根据当前焦点与修饰键路由：

| 按键 | 行为 |
|------|------|
| `Enter` | `input.submit()` → 发 `FrontendRequest(SUBMIT_LINE)` |
| `Ctrl+C` | 取消当前请求（`QueryEngine.cancel`） |
| `Ctrl+D` / `/exit` | 发 `FrontendRequest(SHUTDOWN)` |
| `Up` / `Down` | 历史输入回溯 |
| `Tab` | 命令/文件名补全 |
| `PgUp` / `PgDn` | Transcript 滚动 |
| 其他可打印字符 | `appendChar` |

## 7. 前后端通信协议（`BackendHost`）

### 7.1 `FrontendRequest`（UI → Backend）

```java
enum FrontendRequestType {
    SUBMIT_LINE,            // "type": "submit_line"
    SHUTDOWN,               // "type": "shutdown"
    PERMISSION_RESPONSE,    // "type": "permission_response"
    QUESTION_RESPONSE,      // "type": "question_response"
    LIST_SESSIONS           // "type": "list_sessions"
}

class FrontendRequest {
    FrontendRequestType type;
    String line;           // submit_line 时
    String requestId;      // permission/question 时回指
    Boolean allowed;       // permission_response
    String answer;         // question_response
}
```

### 7.2 `BackendEvent`（Backend → UI）

```java
enum BackendEventType {
    READY, SHUTDOWN, ERROR,
    TRANSCRIPT_ITEM, ASSISTANT_DELTA, ASSISTANT_COMPLETE,
    TOOL_STARTED, TOOL_COMPLETED,
    CLEAR_TRANSCRIPT, LINE_COMPLETE,
    STATUS_SNAPSHOT, TASKS_SNAPSHOT,
    MODAL_REQUEST, SELECT_REQUEST
}
```

工厂方法便于生产：

```java
BackendEvent.ready();
BackendEvent.error("xxx");
BackendEvent.assistantDelta(text);
BackendEvent.toolStarted(toolName, toolInput);
BackendEvent.toolCompleted(toolName, output, isError);
```

### 7.3 `BackendHostConfig`

```java
new BackendHostConfig(model, baseUrl, systemPrompt, apiKey);
```

供 `BackendHost` 初始化 `QueryEngine` 使用。

### 7.4 协议格式

**JSON Lines**（NDJSON）：每行一个 JSON 对象。优点是易解析、易调试（`tail -f` 即可观察），缺点是不能直接带二进制（图片须 base64 或落地）。

使用场景：
- 分离的前后端调试（后端独立进程、UI 另起）
- MCP-style 远程 UI（未来）

## 8. 模态交互

### 8.1 权限确认（`MODAL_REQUEST`）

当 `PermissionChecker.evaluate` 返回 `requiresConfirmation` 时：

```
Backend → MODAL_REQUEST {modal: {...message...}, requestId: "xxx"}
UI      → 弹出确认框
User    → 按 [y/N]
UI      → PERMISSION_RESPONSE {requestId: "xxx", allowed: true}
```

### 8.2 多选问题（`SELECT_REQUEST`）

```
Backend → SELECT_REQUEST {selectOptions: [{id, label}, ...], requestId}
UI      → 显示选项列表，↑↓ 选择，Enter 确认
UI      → QUESTION_RESPONSE {requestId, answer: "<optionId>"}
```

## 9. 主题与渲染细节

- 默认配色读取自 `AppState.theme`
- 颜色通过 Lanterna `TextColor.ANSI.*` 指定，兼容绝大多数终端
- 终端宽度变化由 Lanterna 的 `TerminalResizeListener` 捕获，触发 `requestRepaint`

## 10. Headless / 行模式

`ConsoleInteractiveSession` 提供**非 Lanterna** 的替代方案：用纯 ANSI 转义码在普通终端里实现交互，不接管整个屏幕。适用于：
- 远程 SSH 会话中 Lanterna 支持不稳定的场景
- CI / 日志友好的 `--print` 一次性输出
- `--no-ui` 纯 stdin/stdout 管道模式

底层仍是同一个 `QueryEngine`，只是换了前端渲染器。

## 11. 性能要点

- **增量渲染**：`requestRepaint` 采用合并策略，同一帧内多次调用只触发一次 `Screen.refresh()`
- **流式响应节流**：`ASSISTANT_DELTA` 频率过高时合并为 ~16ms 一次 repaint，避免闪烁
- **Transcript 截断**：旧条目丢弃，防止长会话耗尽内存

## 12. 扩展

如果想接入 **Web UI** 或 **VS Code Extension**：
1. 复用 `BackendHost` 的 `FrontendRequest` / `BackendEvent` 协议
2. 把传输层从"同进程调用"换成 WebSocket / IPC
3. 原有的 `QueryEngine` 与所有工具无需改动

这也是 `BackendEvent` 枚举加了 `@JsonProperty` snake_case 映射的原因——为外部消费者保留通用性。

---

## 🔗 相关文档

- [03-整体架构](03-整体架构.md) — UI 层在整体架构中的位置
- [08-权限系统](08-权限系统.md) — 确认模态的触发来源
- [16-配置管理](16-配置管理.md) — `AppState` / `AppStateStore`
