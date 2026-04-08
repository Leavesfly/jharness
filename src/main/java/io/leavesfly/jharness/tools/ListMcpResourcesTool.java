package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.integration.mcp.McpClientManager;
import io.leavesfly.jharness.integration.mcp.types.McpConnectionStatus;
import io.leavesfly.jharness.tools.input.ListMcpResourcesToolInput;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 列出所有 MCP 服务器的可用资源
 * 
 * 此工具列出所有已连接的 MCP 服务器中的资源，
 * 包括资源 URI、名称和描述。
 */
public class ListMcpResourcesTool extends BaseTool<ListMcpResourcesToolInput> {
    private final McpClientManager mcpManager;

    public ListMcpResourcesTool(McpClientManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    @Override
    public String getName() {
        return "list_mcp_resources";
    }

    @Override
    public String getDescription() {
        return "List all available MCP resources from all connected servers. " +
               "Returns a formatted list of resources with their URI, name, and description.";
    }

    @Override
    public Class<ListMcpResourcesToolInput> getInputClass() {
        return ListMcpResourcesToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(ListMcpResourcesToolInput input, ToolExecutionContext context) {
        try {
            List<McpConnectionStatus.McpResourceInfo> resources = mcpManager.listResources();
            
            if (resources.isEmpty()) {
                return CompletableFuture.completedFuture(
                    ToolResult.success("No MCP resources available.")
                );
            }

            // 格式化为 "server:uri name - description" 格式
            String formatted = resources.stream()
                .map(r -> String.format("%s:%s %s - %s", 
                    r.getServerName(), 
                    r.getUri(), 
                    r.getName(), 
                    r.getDescription() != null ? r.getDescription() : ""))
                .collect(Collectors.joining("\n"));

            String output = String.format("Available MCP resources (%d):\n%s", 
                resources.size(), formatted);

            return CompletableFuture.completedFuture(ToolResult.success(output));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Failed to list MCP resources: " + e.getMessage())
            );
        }
    }

    @Override
    public boolean isReadOnly(ListMcpResourcesToolInput input) {
        return true;
    }
}
