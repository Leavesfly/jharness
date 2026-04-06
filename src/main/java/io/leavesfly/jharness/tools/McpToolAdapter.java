package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.mcp.McpClientManager;
import io.leavesfly.jharness.mcp.types.McpConnectionStatus;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 工具适配器
 * 
 * 将 MCP 工具包装为标准的 JHarness 工具。
 */
public class McpToolAdapter extends BaseTool<Map<String, Object>> {
    
    private final McpClientManager manager;
    private final McpConnectionStatus.McpToolInfo toolInfo;
    private final String adapterName;
    
    public McpToolAdapter(McpClientManager manager, McpConnectionStatus.McpToolInfo toolInfo) {
        this.manager = manager;
        this.toolInfo = toolInfo;
        
        // 构建工具名称：mcp__{server}__{tool}
        String serverSegment = sanitize(toolInfo.getServerName());
        String toolSegment = sanitize(toolInfo.getName());
        this.adapterName = "mcp__" + serverSegment + "__" + toolSegment;
    }
    
    @Override
    public String getName() {
        return adapterName;
    }
    
    @Override
    public String getDescription() {
        return toolInfo.getDescription() != null ? toolInfo.getDescription() 
            : "MCP 工具: " + toolInfo.getName();
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public Class<Map<String, Object>> getInputClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }
    
    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> arguments, ToolExecutionContext context) {
        return manager.callTool(toolInfo.getServerName(), toolInfo.getName(), arguments)
            .thenApply(result -> ToolResult.success(result))
            .exceptionally(e -> ToolResult.error("MCP 工具执行失败: " + e.getMessage()));
    }
    
    /**
     * 清理名称，只保留字母、数字、下划线和连字符
     */
    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9_-]", "_");
        if (sanitized.isEmpty()) {
            return "tool";
        }
        if (!Character.isLetter(sanitized.charAt(0))) {
            return "mcp_" + sanitized;
        }
        return sanitized;
    }
}
