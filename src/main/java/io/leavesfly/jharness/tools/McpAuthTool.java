package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.integration.mcp.McpClientManager;
import io.leavesfly.jharness.tools.input.McpAuthToolInput;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 配置 MCP 服务器的认证信息
 * 
 * 此工具支持三种认证模式：
 * - bearer: 设置 Bearer Token 认证
 * - header: 设置自定义 Header 认证
 * - env: 设置环境变量认证
 * 
 * 配置更新后会自动重新连接 MCP 服务器。
 */
public class McpAuthTool extends BaseTool<McpAuthToolInput> {
    private final McpClientManager mcpManager;
    private final Settings settings;

    public McpAuthTool(McpClientManager mcpManager, Settings settings) {
        this.mcpManager = mcpManager;
        this.settings = settings;
    }

    @Override
    public String getName() {
        return "mcp_auth";
    }

    @Override
    public String getDescription() {
        return "Configure authentication for MCP servers. Supports bearer token, custom headers, " +
               "and environment variable authentication. Automatically reconnects servers after config changes.";
    }

    @Override
    public Class<McpAuthToolInput> getInputClass() {
        return McpAuthToolInput.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<ToolResult> execute(McpAuthToolInput input, ToolExecutionContext context) {
        if (input.getServerName() == null || input.getServerName().isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Parameter 'serverName' is required")
            );
        }
        if (input.getMode() == null || input.getMode().isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Parameter 'mode' is required (bearer, header, or env)")
            );
        }
        if (input.getValue() == null || input.getValue().isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Parameter 'value' is required")
            );
        }

        String serverName = input.getServerName();
        String mode = input.getMode().toLowerCase();
        String value = input.getValue();
        String key = input.getKey();

        try {
            // 获取当前服务器配置
            Map<String, Object> currentConfig = getCurrentConfig(serverName);
            
            // 根据模式更新认证配置
            switch (mode) {
                case "bearer":
                    updateBearerAuth(currentConfig, value);
                    break;
                case "header":
                    updateHeaderAuth(currentConfig, key != null ? key : "Authorization", value);
                    break;
                case "env":
                    updateEnvAuth(currentConfig, key != null ? key : "MCP_AUTH_TOKEN", value);
                    break;
                default:
                    return CompletableFuture.completedFuture(
                        ToolResult.error("Invalid mode: " + mode + ". Must be 'bearer', 'header', or 'env'")
                    );
            }

            // 更新服务器配置
            mcpManager.updateServerConfig(serverName, currentConfig);

            // 重新连接所有服务器以应用更改（异步触发，不阻塞当前线程）
            mcpManager.reconnectAll();

            String message = String.format(
                "MCP authentication updated for server '%s' (mode: %s). Servers reconnecting...",
                serverName, mode
            );

            return CompletableFuture.completedFuture(ToolResult.success(message));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Failed to configure MCP auth: " + e.getMessage())
            );
        }
    }

    @Override
    public boolean isReadOnly(McpAuthToolInput input) {
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getCurrentConfig(String serverName) {
        // 从 Settings 获取当前 MCP 服务器配置
        Map<String, Object> mcpServers = settings.getMcpServers();
        if (mcpServers == null) {
            mcpServers = new HashMap<>();
            settings.setMcpServers(mcpServers);
        }

        Map<String, Object> serverConfig = (Map<String, Object>) mcpServers.get(serverName);
        if (serverConfig == null) {
            serverConfig = new HashMap<>();
            serverConfig.put("type", "stdio"); // 默认类型
            mcpServers.put(serverName, serverConfig);
        }

        return serverConfig;
    }

    @SuppressWarnings("unchecked")
    private void updateBearerAuth(Map<String, Object> config, String token) {
        String type = (String) config.getOrDefault("type", "http");
        
        if ("http".equals(type) || "https".equals(type)) {
            // HTTP 类型使用 headers
            Map<String, String> headers = (Map<String, String>) config.get("headers");
            if (headers == null) {
                headers = new HashMap<>();
                config.put("headers", headers);
            }
            headers.put("Authorization", "Bearer " + token);
        } else {
            // stdio 类型使用环境变量
            Map<String, String> env = (Map<String, String>) config.get("env");
            if (env == null) {
                env = new HashMap<>();
                config.put("env", env);
            }
            env.put("MCP_AUTH_TOKEN", token);
        }
    }

    @SuppressWarnings("unchecked")
    private void updateHeaderAuth(Map<String, Object> config, String headerKey, String headerValue) {
        String type = (String) config.getOrDefault("type", "http");
        
        if (!"http".equals(type) && !"https".equals(type)) {
            throw new IllegalArgumentException("Header authentication is only supported for HTTP/HTTPS servers");
        }

        Map<String, String> headers = (Map<String, String>) config.get("headers");
        if (headers == null) {
            headers = new HashMap<>();
            config.put("headers", headers);
        }
        headers.put(headerKey, headerValue);
    }

    @SuppressWarnings("unchecked")
    private void updateEnvAuth(Map<String, Object> config, String envKey, String envValue) {
        String type = (String) config.getOrDefault("type", "stdio");
        
        if (!"stdio".equals(type)) {
            throw new IllegalArgumentException("Environment variable authentication is only supported for stdio servers");
        }

        Map<String, String> env = (Map<String, String>) config.get("env");
        if (env == null) {
            env = new HashMap<>();
            config.put("env", env);
        }
        env.put(envKey, envValue);
    }
}
