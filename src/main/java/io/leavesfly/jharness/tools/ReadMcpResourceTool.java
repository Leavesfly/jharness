package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.mcp.McpClientManager;
import io.leavesfly.jharness.tools.input.ReadMcpResourceToolInput;

import java.util.concurrent.CompletableFuture;

/**
 * 读取特定 MCP 服务器的资源内容
 * 
 * 此工具通过服务器名称和 URI 读取 MCP 资源的实际内容。
 */
public class ReadMcpResourceTool extends BaseTool<ReadMcpResourceToolInput> {
    private final McpClientManager mcpManager;

    public ReadMcpResourceTool(McpClientManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    @Override
    public String getName() {
        return "read_mcp_resource";
    }

    @Override
    public String getDescription() {
        return "Read the contents of a specific MCP resource by server name and URI. " +
               "Use list_mcp_resources first to discover available resources.";
    }

    @Override
    public Class<ReadMcpResourceToolInput> getInputClass() {
        return ReadMcpResourceToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(ReadMcpResourceToolInput input, ToolExecutionContext context) {
        if (input.getServer() == null || input.getServer().isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Parameter 'server' is required")
            );
        }
        if (input.getUri() == null || input.getUri().isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Parameter 'uri' is required")
            );
        }

        return mcpManager.readResource(input.getServer(), input.getUri())
            .thenApply(content -> {
                if (content.startsWith("错误:") || content.startsWith("资源读取失败:")) {
                    return ToolResult.error(content);
                }
                return ToolResult.success(content);
            })
            .exceptionally(e -> ToolResult.error(
                "Failed to read MCP resource: " + e.getMessage()
            ));
    }

    @Override
    public boolean isReadOnly(ReadMcpResourceToolInput input) {
        return true;
    }
}
