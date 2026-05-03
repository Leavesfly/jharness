# 12 - MCP 协议客户端

> 位于 `io.leavesfly.jharness.integration.mcp`。JHarness 作为 **MCP Client**，可接入符合 Model Context Protocol 的外部服务器，以外部工具（Tools）与外部资源（Resources）的形式暴露给 Agent。

## 1. 概念速览

Model Context Protocol (MCP) 是 Anthropic 主推的开放协议：
- 一个 MCP **Server** 暴露自己的 `tools` 与 `resources`
- 一个 MCP **Client**（如 JHarness）连接 Server，把它们注入到 LLM 会话中
- 支持多种传输：**stdio / http / websocket**

常见 MCP Server 示例：
- 文件系统服务器
- Git 服务器
- 数据库查询服务器
- 公司内部 API 代理

## 2. 核心组件

| 类 | 职责 |
|---|------|
| `McpServerConfig` | 单个 Server 的配置 |
| `McpClient` / `StdioMcpClient` / `HttpMcpClient` | 传输层实现 |
| `McpClientManager` | 多 Server 管理 + 生命周期 |
| `McpConnectionStatus` | 连接状态与工具/资源清单 |
| `McpToolAdapter` | 把 MCP 工具包装为 `BaseTool` |

## 3. `McpServerConfig`：Server 配置

```java
public class McpServerConfig {
    String name;
    String type;                // "stdio" / "http" / "websocket"
    // stdio
    String command;
    List<String> args;
    Map<String,String> env;
    // http / websocket
    String url;
    Map<String,String> headers;
    // 通用
    int timeoutMs;
    boolean enabled;
}
```

### 3.1 stdio 配置示例

```json
{
  "mcpServers": {
    "fs": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"],
      "env": { "NODE_ENV": "production" }
    }
  }
}
```

JHarness 会 fork 子进程并与其 stdin/stdout 做 **JSON-RPC 2.0** 对话。

### 3.2 http 配置示例

```json
{
  "mcpServers": {
    "intranet-db": {
      "type": "http",
      "url": "https://mcp.intranet.example.com/rpc",
      "headers": { "Authorization": "Bearer ${TOKEN}" },
      "timeoutMs": 15000
    }
  }
}
```

URL 会经过 `UrlSafetyValidator.validate(url)`：拒绝非 http/https 协议、loopback、link-local、site-local、CGNAT 地址等（详见 [08-权限系统 §9](08-权限系统.md)）。

### 3.3 websocket 配置

类似 http，但协议为 `ws` / `wss`。实现用 OkHttp 的 `WebSocket` API。

## 4. `McpConnectionStatus`：状态模型

```java
public class McpConnectionStatus {
    String serverName;
    boolean connected;
    String error;
    List<McpToolInfo> tools;
    List<McpResourceInfo> resources;

    public static class McpToolInfo {
        String name;
        String description;
        Map<String,Object> inputSchema;
    }
    public static class McpResourceInfo {
        String uri;
        String name;
        String mimeType;
    }
}
```

状态在每次连接 / 刷新时由 `McpClientManager` 更新。

## 5. `McpClientManager`：多 Server 管理

### 5.1 API

```java
McpClientManager mgr = new McpClientManager(cwd);

mgr.addServer(McpServerConfig cfg);
mgr.removeServer(String name);
mgr.reconnect(String name);
mgr.reconnectAll();

List<McpConnectionStatus> statuses = mgr.listStatuses();
List<BaseTool> tools = mgr.getAggregatedTools();    // 所有 Server 工具合并为 BaseTool 列表

mgr.setPermissionChecker(checker);                   // 工具调用走同一权限栈
mgr.shutdown();                                      // 关闭所有客户端
```

### 5.2 生命周期

```
启动：
  loadFromConfig(~/.jharness/mcp.json)
  loadFromConfig(<cwd>/.jharness/mcp.json)
  for each plugin in LoadedPlugin.mcpServers: addServer(...)
  对每个 enabled Server: connect() 异步
    → initialize
    → listTools   → 缓存到 status.tools
    → listResources → 缓存到 status.resources

运行：
  QueryEngine 通过 getAggregatedTools() 把 MCP 工具一起交给 LLM
  LLM 调用某 MCP 工具时：
    → McpToolAdapter.execute(input)
    → 走 permissionChecker.evaluate
    → client.callTool(toolName, input) → JSON-RPC request
    → 返回结果给 ToolExecutor

关闭：
  shutdown() → 每个 client close → 释放进程 / 关闭连接
```

## 6. `StdioMcpClient`：stdio 传输细节

- 通过 `ProcessBuilder.start()` 启动 Server 进程
- 一个 **读线程** 阻塞读 Server stdout，按 `Content-Length` 头（LSP-style 框架）切分帧
- 一个 **写路径** 把请求 JSON 通过 Server stdin 发送
- 维护 `Map<String, CompletableFuture<JsonRpcResponse>>`（按 `id` 关联请求与响应）
- 超时使用 `CompletableFuture#orTimeout`
- Server 进程存活由 `Process.isAlive` + stderr 消耗线程保障

**环境变量传播**：从 `cfg.env` 合并到 `ProcessBuilder.environment()`，**不继承** `JHARNESS_*` 调试变量（避免泄露内部 PII）。

## 7. `HttpMcpClient`：http 传输细节

- 基于 OkHttp：每次 RPC 都是一次 `POST {url}`，body 为 JSON-RPC 请求
- 支持 keep-alive 与连接池
- 请求头带上 `Content-Type: application/json`、用户自定义 `headers`
- 每次请求都额外过一次 `UrlSafetyValidator.validate`（防止 DNS rebinding 被换解析）
- 默认 `timeoutMs=15000` 做 call timeout

## 8. `McpToolAdapter`：桥接到 BaseTool

```java
public class McpToolAdapter extends BaseTool {
    private final McpClient client;
    private final String remoteToolName;
    private final Map<String,Object> inputSchema;

    @Override public String getName() { return "mcp__" + serverName + "__" + remoteToolName; }
    @Override public Map<String,Object> getInputSchema() { return inputSchema; }
    @Override public ToolResult execute(ToolContext ctx) throws Exception {
        // 1. 权限检查
        // 2. client.callTool(remoteToolName, ctx.getInput())
        // 3. 转换响应 → ToolResult
    }
}
```

命名规则：`mcp__<serverName>__<toolName>`，与 Claude Code 生态一致，方便过白名单。

## 9. 资源（Resources）

MCP 还支持 `resources/read`：读取 Server 暴露的资源（文件、记录等）。JHarness 将资源统一以 `mcp__<server>__resource` 名字或 `/mcp resource ...` 命令暴露。

**当前实现重点在 Tools**；Resources 主要用于 `/mcp` 命令展示。

## 10. `/mcp` 命令

由 `McpCommandHandler.createMcpCommand` 注册：

- `/mcp list` — 列出所有 Server 与连接状态
- `/mcp tools <server>` — 列出某 Server 暴露的工具
- `/mcp reconnect <server>` — 重连
- `/mcp add` / `/mcp remove` — 运行时增删 Server

## 11. 配置文件合并顺序

启动时按以下顺序加载，后者追加不覆盖（按 Server name 去重）：

1. `~/.jharness/mcp.json`（用户全局）
2. `<cwd>/.jharness/mcp.json`（项目）
3. 所有启用插件的 `mcp.json`（详见 [10](10-插件与技能系统.md)）

## 12. 安全与可靠性要点

| 风险 | 对策 |
|------|------|
| 远程 URL 指向内网 | `UrlSafetyValidator` 前置校验 |
| MCP 工具滥用敏感操作 | 共享同一 `PermissionChecker`（工具黑白名单按 `mcp__*` 通配可配） |
| Server 进程崩溃 | `Process` 监控 + 自动标记 `connected=false`，可通过 `/mcp reconnect` 重连 |
| Server 响应过大 | OkHttp response body 读取加尺寸上限；stdio 帧大小上限 |
| 子进程泄露环境变量 | 显式白名单化传入环境变量，而非全盘继承 |
| JSON 注入 | 全程使用 `JacksonUtils.MAPPER`，`deactivateDefaultTyping` 防反序列化 RCE |

---

## 🔗 相关文档

- [06-工具系统](06-工具系统.md) — BaseTool 抽象
- [08-权限系统](08-权限系统.md) — URL 安全 & 工具权限
- [10-插件与技能系统](10-插件与技能系统.md) — 插件如何提供 mcp.json
