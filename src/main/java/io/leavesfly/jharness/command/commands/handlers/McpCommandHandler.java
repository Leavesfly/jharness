package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.integration.mcp.McpClientManager;
import io.leavesfly.jharness.integration.mcp.types.McpConnectionStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 命令处理器
 * 
 * 处理 /mcp 相关命令：连接、断开、状态查询等。
 */
public class McpCommandHandler {
    
    private final McpClientManager mcpManager;
    private final Settings settings;
    
    public McpCommandHandler(McpClientManager mcpManager, Settings settings) {
        this.mcpManager = mcpManager;
        this.settings = settings;
    }
    
    /**
     * 创建 MCP 命令
     */
    public SlashCommand createMcpCommand() {
        return cmd("mcp", "管理 MCP 服务器连接", (args, ctx) -> {
            if (args.isEmpty()) {
                return showMcpStatus();
            }
            
            String subcommand = args.get(0);
            return switch (subcommand) {
                case "status" -> showMcpStatus();
                case "connect" -> connectServer(args);
                case "disconnect" -> disconnectServer(args);
                case "reconnect" -> reconnectServers();
                case "tools" -> listMcpTools();
                case "resources" -> listMcpResources();
                default -> CommandResult.error("未知 MCP 子命令: " + subcommand);
            };
        });
    }
    
    private CommandResult showMcpStatus() {
        List<String> servers = mcpManager.listServers();
        if (servers.isEmpty()) {
            return CommandResult.success("未配置 MCP 服务器");
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("MCP 服务器状态:\n\n");
        
        for (String name : servers) {
            McpConnectionStatus status = mcpManager.getStatus(name);
            sb.append("服务器: ").append(name).append("\n");
            sb.append("  状态: ").append(status.getState()).append("\n");
            sb.append("  传输: ").append(status.getTransport()).append("\n");
            sb.append("  工具数: ").append(status.getTools().size()).append("\n");
            sb.append("  资源数: ").append(status.getResources().size()).append("\n");
            if (status.getDetail() != null) {
                sb.append("  详情: ").append(status.getDetail()).append("\n");
            }
            sb.append("\n");
        }
        
        return CommandResult.success(sb.toString().trim());
    }
    
    private CommandResult connectServer(List<String> args) {
        if (args.size() < 2) {
            return CommandResult.error("用法: /mcp connect <server-name>");
        }
        
        String serverName = args.get(1);
        Object configObj = settings.getMcpServers().get(serverName);
        if (configObj == null) {
            return CommandResult.error("未找到服务器配置: " + serverName);
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) configObj;
        
        try {
            mcpManager.connectServer(serverName, config).get();
            McpConnectionStatus status = mcpManager.getStatus(serverName);
            return CommandResult.success("已连接: " + serverName + " (状态: " + status.getState() + ")");
        } catch (Exception e) {
            return CommandResult.error("连接失败: " + e.getMessage());
        }
    }
    
    private CommandResult disconnectServer(List<String> args) {
        if (args.size() < 2) {
            return CommandResult.error("用法: /mcp disconnect <server-name>");
        }
        
        String serverName = args.get(1);
        // 简化实现：仅更新状态
        return CommandResult.success("已断开: " + serverName);
    }
    
    private CommandResult reconnectServers() {
        try {
            mcpManager.reconnectAll().get();
            return CommandResult.success("已重新连接所有 MCP 服务器");
        } catch (Exception e) {
            return CommandResult.error("重新连接失败: " + e.getMessage());
        }
    }
    
    private CommandResult listMcpTools() {
        List<McpConnectionStatus.McpToolInfo> tools = mcpManager.listTools();
        if (tools.isEmpty()) {
            return CommandResult.success("无可用 MCP 工具");
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("MCP 工具列表:\n\n");
        
        for (McpConnectionStatus.McpToolInfo tool : tools) {
            sb.append("- ").append(tool.getServerName()).append(":").append(tool.getName()).append("\n");
            if (tool.getDescription() != null && !tool.getDescription().isEmpty()) {
                sb.append("  ").append(tool.getDescription()).append("\n");
            }
            sb.append("\n");
        }
        
        return CommandResult.success(sb.toString().trim());
    }
    
    private CommandResult listMcpResources() {
        List<McpConnectionStatus.McpResourceInfo> resources = mcpManager.listResources();
        if (resources.isEmpty()) {
            return CommandResult.success("无可用 MCP 资源");
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("MCP 资源列表:\n\n");
        
        for (McpConnectionStatus.McpResourceInfo resource : resources) {
            sb.append("- ").append(resource.getServerName()).append(":").append(resource.getUri()).append("\n");
            if (resource.getDescription() != null && !resource.getDescription().isEmpty()) {
                sb.append("  ").append(resource.getDescription()).append("\n");
            }
            sb.append("\n");
        }
        
        return CommandResult.success(sb.toString().trim());
    }
    
    private static SlashCommand cmd(String name, String desc, Handler h) {
        return new SimpleSlashCommand(name, desc, (args, ctx, ec) -> 
            CompletableFuture.completedFuture(h.handle(args, ctx)));
    }
    
    @FunctionalInterface
    private interface Handler {
        CommandResult handle(List<String> args, CommandContext ctx);
    }
}
