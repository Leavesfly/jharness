package io.leavesfly.jharness.integration.mcp.session;

import io.leavesfly.jharness.integration.mcp.types.McpConnectionStatus;

import java.util.List;
import java.util.Map;

/**
 * MCP 会话抽象。
 *
 * 一个 McpSession 代表与单个 MCP 服务器（stdio 子进程或 HTTP 端点）建立的活动连接，
 * 屏蔽底层传输细节，统一暴露 JSON-RPC 语义层方法：
 * initialize / listTools / listResources / callTool / readResource / close。
 */
public interface McpSession {

    void initialize() throws Exception;

    List<McpConnectionStatus.McpToolInfo> listTools() throws Exception;

    List<McpConnectionStatus.McpResourceInfo> listResources() throws Exception;

    String callTool(String toolName, Map<String, Object> arguments) throws Exception;

    String readResource(String uri) throws Exception;

    void close() throws Exception;
}
