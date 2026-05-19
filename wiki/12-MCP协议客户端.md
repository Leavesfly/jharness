# 12 · MCP 协议客户端

> 包路径：`io.leavesfly.jharness.integration.mcp`
>
> 核心类：`McpClientManager`（管理者） + `McpSession`（会话接口） + `StdioMcpSession` / `HttpMcpSession`

---

## 1. 什么是 MCP

**Model Context Protocol (MCP)** 是 Anthropic 提出的"AI Agent ↔ 工具/资源/Prompt"通用协议（JSON-RPC 2.0），让任何"能讲 MCP 的服务器"都能被 JHarness 当作工具调用。

JHarness 作为 **MCP 客户端**：

- 启动时扫描配置文件中声明的 MCP 服务器
- 通过 stdio 或 HTTP 与每个服务器建立会话
- 调 `tools/list` / `resources/list` 拉取能力清单
- 用 `McpToolAdapter` 把远端工具桥接为本地工具（注册到 `ToolRegistry`）
- LLM 调用桥接工具时，转发为 `tools/call` 请求

---

## 2. 支持的传输方式

| 传输 | 类 | 说明 |
|------|-----|------|
| **stdio** | `StdioMcpSession` | fork 子进程，stdin/stdout 走 JSON-RPC，行分隔 |
| **HTTP** | `HttpMcpSession` | POST + SSE，兼容 MCP Streamable HTTP |
| **WebSocket** | `McpWebSocketServerConfig`（配置占位） | 当前未实现 session |

---

## 3. 配置文件格式

`~/.jharness/mcp-servers.json` 或 `<cwd>/.jharness/mcp-servers.json`：

```json
{
  "mcpServers": {
    "remote-tools": {
      "type": "http",
      "url": "https://my-mcp.example.com/mcp",
      "headers": { "Authorization": "Bearer my-token-xxx" }
    }
  }
}
```

由 `McpServerConfig`（Jackson 多态）反序列化为三个子类之一。

---

## 4. `McpClientManager`

### 4.1 核心字段

```java
class McpClientManager {
    Map<String, Map<String, Object>> serverConfigs;  // 名 → 原始 config map
    Map<String, McpConnectionStatus> statuses;        // 名 → 连接状态
    Map<String, McpSession> sessions;                 // 名 → 会话实例
    ExecutorService executor;                         // 有界线程池
    OkHttpClient sharedHttpClient;                    // 复用 HTTP 客户端
    volatile PermissionGate permissionChecker;        // 注入的权限闸门
}
```

### 4.2 主要 API

```java
void addServer(String name, Map<String, Object> config);
CompletableFuture<Void> connectAll();                          // 异步连接所有
CompletableFuture<Void> connectServer(String name, Map cfg);
void onConnected(Runnable listener);                            // 连接完成回调（幂等）
List<McpToolInfo> listTools();
List<McpResourceInfo> listResources();
CompletableFuture<String> callTool(String server, String tool, Map<String, Object> args);
CompletableFuture<String> readResource(String server, String uri);
McpConnectionStatus getStatus(String name);
CompletableFuture<Void> reconnectAll();
void close();                                                    // 关闭所有 session + 线程池
```

### 4.3 OkHttpClient 配置（HTTP 安全）

```java
new OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .followRedirects(false)       // SSRF 防护
    .followSslRedirects(false)    // 防止 302 跳内网
    .build();
```

---

## 5. 连接完成回调（onConnected）

`McpClientManager.connectAll()` 是异步的，`ToolRegistry.withDefaults()` 注册时 MCP 工具列表可能还是空：

```java
mcpManager.onConnected(() -> {
    int added = toolRegistry.refreshMcpTools(mcpManager);
    logger.info("MCP 连接完成后新增 {} 个动态工具", added);
});
```

**幂等保证**：若注册监听器时已有 `connected` 状态的服务器，立即触发一次回调，避免 `addServer` 在 `connectAll` 之后发生导致漏触发。

---

## 6. stdio 会话（`StdioMcpSession`）

### 6.1 协议

- 子进程 stdin / stdout 都是行分隔 JSON-RPC 消息
- 每条消息一行 JSON
- stderr 仅作日志记录

### 6.2 启动子进程

```java
ProcessBuilder pb = new ProcessBuilder(command, args);
if (env != null) pb.environment().putAll(env);
if (cwd != null) pb.directory(new File(cwd));

Process p = pb.start();
```

### 6.3 权限闸门

在 fork 前调用 `permissionChecker.evaluate("bash", false, null, command + " " + args)`（工具名固定用 `"bash"`，与前台 BashTool 名称一致，确保黑名单规则前后台共用）：

- `deny`（`!isAllowed() && !isRequiresConfirmation()`）→ 抛 `SecurityException("MCP stdio 命令被权限拒绝: " + reason)`，连接失败
- `allow` / `requiresConfirmation` → 都视为放行，继续 fork 子进程（**MCP 路径不弹用户确认**，因为连接是后台动作；如需更严格控制，请在 `deniedCommandPatterns` 中直接禁止对应命令）

这是 **P0 关键安全特性**：防止通过 MCP 配置绕过命令黑名单（如配 `rm -rf /` 作为 MCP server 命令）。

### 6.4 请求 / 响应配对

每个请求带递增 `id`（`AtomicInteger`），响应按 id 匹配到 `CompletableFuture`。30s 超时未响应自动 fail。

### 6.5 关闭

- `close()` 发送 `notifications/cancelled` → 关闭 stdin → 等待 5s → 强杀

---

## 7. HTTP 会话（`HttpMcpSession`）

### 7.1 协议（Streamable HTTP）

- 客户端 POST JSON-RPC 请求到指定 URL
- 服务器响应可以是：
  - 同步 JSON（普通请求）
  - SSE 流（长任务，逐条事件返回结果）
- 客户端用 `Accept: application/json, text/event-stream` 协商

### 7.2 SSRF 防护

URL 经过 `UrlSafetyValidator`（详见 `util/UrlSafetyValidator.java`）：

- 禁止 `file://` / `data://` / `javascript://`
- 禁止 IPv4 私有段（10/8、172.16/12、192.168/16）/ loopback / link-local
- 禁止 IPv6 私有段（fd00::/8、fe80::/10、::1）
- 禁止 `0.0.0.0`、`169.254.169.254`（云元数据服务）

URL 校验由 `util.UrlSafetyValidator.validate(url)` 完成，**当前没有"放开本地端点"的开关**；如确实需要连接本地 MCP HTTP 服务器，请使用 `stdio` 传输（在本机 fork 进程）或修改 `UrlSafetyValidator` 源码。

### 7.3 Auth Header

`McpServerConfig.McpHttpServerConfig.headers` 中可放任意 header，常见 `Authorization: Bearer xxx`。

由 `command/builtin/mcp/McpAuthTool` 提供 OAuth flow 辅助（按需）。

---

## 8. MCP 工具适配（`McpToolAdapter`）

每个远端工具被包装成 `BaseTool<Map<String, Object>>`：

```java
class McpToolAdapter extends BaseTool<Map<String, Object>> {
    private final McpClientManager manager;
    private final String serverName;
    private final McpToolInfo info;     // name / description / inputSchema

    @Override public String getName() { return info.getName(); }
    @Override public String getDescription() { return info.getDescription(); }
    @Override public boolean isReadOnly(Map input) { return info.isReadOnly(); }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> input, ToolExecutionContext ctx) {
        return manager.callTool(serverName, info.getName(), input)
            .thenApply(ToolResult::success);
    }

    @Override
    public Map<String, Object> toApiSchema() {
        // 直接透传服务器返回的 inputSchema（已经是 JSON Schema）
        return info.getInputSchema();
    }
}
```

### 8.1 工具命名空间

避免多个 MCP 服务器工具重名，工具注册时会用 **`mcp__{server}__{tool}`** 作为最终 name（如 `mcp__filesystem__read_file`），由 `McpToolAdapter` 在构造时拼接。`server` 与 `tool` 段都会经过 `sanitize`（非 `[A-Za-z0-9_]` 字符替换为下划线），确保 LLM 协议层（OpenAI `tools[].function.name`）合法。

---

## 9. MCP 资源

MCP 还支持"Resources"概念（类似 REST URI 资源），由两个工具暴露：

| 工具 | 行为 |
|------|------|
| `list_mcp_resources` | 调用 `manager.listResources()` 返回所有 server 的资源列表 |
| `read_mcp_resource` | 按 `<serverName>::<uri>` 调 `manager.readResource(server, uri)` |

适合"把远端文件 / 数据库 / API 当资源拉过来给 LLM 阅读"的场景。

---

## 10. `/mcp` 命令

| 命令 | 行为 |
|------|------|
| `/mcp list` | 列出所有服务器及连接状态 |
| `/mcp add <json>` | 运行时添加（不持久化） |
| `/mcp remove <name>` | 移除 |
| `/mcp reconnect [<name>]` | 重连 |
| `/mcp tools` | 列出所有 MCP 桥接工具 |
| `/mcp resources` | 列出所有 MCP 资源 |
| `/mcp status` | 摘要状态 |

由 `command/builtin/agent/McpCommandHandler` 实现。

---

## 11. JSON-RPC 协议细节

`McpJsonRpc` 类封装 4 个常用方法：

```java
public static Map<String, Object> initialize(int id);          // 握手
public static Map<String, Object> listTools(int id);
public static Map<String, Object> callTool(int id, String name, Map args);
public static Map<String, Object> listResources(int id);
public static Map<String, Object> readResource(int id, String uri);
```

握手成功后才能调其他方法；`StdioMcpSession.connect()` / `HttpMcpSession.connect()` 内部自动完成。

---

## 12. 连接状态（`McpConnectionStatus`）

```java
public class McpConnectionStatus {
    String state;              // pending / connecting / connected / failed / disconnected
    String type;               // stdio / http / ws
    String error;              // 失败原因
    Instant connectedAt;
}
```

`/mcp status` 输出示例：

```
filesystem  [stdio]  connected   2026-05-19T15:30:12
github      [http]   connected   2026-05-19T15:30:14
broken      [stdio]  failed      error="Command not found: foo"
```

---

## 13. 关键类清单

| 类 | 文件 | 行数 | 职责 |
|----|------|------|------|
| `McpClientManager` | `mcp/McpClientManager.java` | 410 | 全生命周期管理 + 权限闸门 |
| `McpSession` | `mcp/session/McpSession.java` | — | 会话接口 |
| `StdioMcpSession` | `mcp/session/StdioMcpSession.java` | 6.1 KB | stdio 子进程会话 |
| `HttpMcpSession` | `mcp/session/HttpMcpSession.java` | 4.7 KB | HTTP/SSE 会话 |
| `McpJsonRpc` | `mcp/session/McpJsonRpc.java` | 6 KB | JSON-RPC 消息构造 |
| `McpExecutorFactory` | `mcp/session/McpExecutorFactory.java` | 1.7 KB | 有界线程池 + 命名 ThreadFactory |
| `McpServerConfig` | `mcp/types/McpServerConfig.java` | 104 | Jackson 多态配置 |
| `McpConnectionStatus` | `mcp/types/McpConnectionStatus.java` | 3.1 KB | 状态 DTO |
| `McpToolAdapter` | `tools/builtin/mcp/McpToolAdapter.java` | — | 远端工具桥接 |
| `ListMcpResourcesTool` | `tools/builtin/mcp/ListMcpResourcesTool.java` | — | 列资源 |
| `ReadMcpResourceTool` | `tools/builtin/mcp/ReadMcpResourceTool.java` | — | 读资源 |
| `McpAuthTool` | `tools/builtin/mcp/McpAuthTool.java` | — | OAuth 辅助 |

---

## 14. 故障排查

| 现象 | 原因 | 修复 |
|------|------|------|
| `Command not found` | stdio command 路径错 | 用绝对路径，或确认 `$PATH` |
| `Permission denied` | 权限规则拒绝 | 检查 `deniedCommandPatterns` 或加 `allowedTools` |
| HTTP 连接 `URL blocked by SSRF guard` | 内网地址 | 配 `mcpAllowLocalEndpoints=true` |
| 工具调用超时 | 远端慢 | 调大 `readTimeout` 或拆分任务 |
| 启动后没有 MCP 工具 | `connectAll()` 还没完成 | `onConnected` 回调里查看；可手动 `/mcp reconnect` |

---

## 15. 下一步

- 工具如何被 LLM 调用 → [06-工具系统](06-工具系统.md)
- 权限闸门细节 → [08-权限系统](08-权限系统.md)
- 集成开发自定义 MCP 客户端 → [17-扩展开发指南 § 4](17-扩展开发指南.md#4-集成自定义-mcp-客户端)
