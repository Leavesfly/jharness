package io.leavesfly.jharness.mcp.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * MCP 连接状态
 */
public class McpConnectionStatus {
    
    public static final McpConnectionStatus DISCONNECTED = new McpConnectionStatus("disconnected", null, null);
    public static final McpConnectionStatus CONNECTED = new McpConnectionStatus("connected", null, null);
    public static final McpConnectionStatus ERROR = new McpConnectionStatus("error", null, null);
    
    private final String state;
    private final String transport;
    private final String detail;
    private final List<McpToolInfo> tools;
    private final List<McpResourceInfo> resources;
    private final boolean authConfigured;
    
    public McpConnectionStatus(String state, String transport, String detail) {
        this(state, transport, detail, false, List.of(), List.of());
    }
    
    public McpConnectionStatus(String state, String transport, String detail, 
                               boolean authConfigured, List<McpToolInfo> tools, List<McpResourceInfo> resources) {
        this.state = state;
        this.transport = transport;
        this.detail = detail;
        this.authConfigured = authConfigured;
        this.tools = tools;
        this.resources = resources;
    }
    
    public String getState() { return state; }
    public String getTransport() { return transport; }
    public String getDetail() { return detail; }
    public List<McpToolInfo> getTools() { return tools; }
    public List<McpResourceInfo> getResources() { return resources; }
    public boolean isAuthConfigured() { return authConfigured; }
    
    /**
     * MCP 工具信息
     */
    public static class McpToolInfo {
        private final String serverName;
        private final String name;
        private final String description;
        private final Map<String, Object> inputSchema;
        
        public McpToolInfo(String serverName, String name, String description, Map<String, Object> inputSchema) {
            this.serverName = serverName;
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
        
        public String getServerName() { return serverName; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public Map<String, Object> getInputSchema() { return inputSchema; }
    }
    
    /**
     * MCP 资源信息
     */
    public static class McpResourceInfo {
        private final String serverName;
        private final String name;
        private final String uri;
        private final String description;
        
        public McpResourceInfo(String serverName, String name, String uri, String description) {
            this.serverName = serverName;
            this.name = name;
            this.uri = uri;
            this.description = description;
        }
        
        public String getServerName() { return serverName; }
        public String getName() { return name; }
        public String getUri() { return uri; }
        public String getDescription() { return description; }
    }
}
