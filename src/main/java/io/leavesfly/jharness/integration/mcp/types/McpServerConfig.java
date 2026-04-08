package io.leavesfly.jharness.integration.mcp.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置类型
 * 支持 stdio、http、websocket 三种传输方式
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = McpServerConfig.McpStdioServerConfig.class, name = "stdio"),
    @JsonSubTypes.Type(value = McpServerConfig.McpHttpServerConfig.class, name = "http"),
    @JsonSubTypes.Type(value = McpServerConfig.McpWebSocketServerConfig.class, name = "ws")
})
public abstract class McpServerConfig {
    
    /**
     * stdio MCP 服务器配置
     */
    public static class McpStdioServerConfig extends McpServerConfig {
        @JsonProperty("command")
        private String command;
        
        @JsonProperty("args")
        private List<String> args = List.of();
        
        @JsonProperty("env")
        private Map<String, String> env;
        
        @JsonProperty("cwd")
        private String cwd;
        
        public McpStdioServerConfig() {}
        
        public McpStdioServerConfig(String command, List<String> args, Map<String, String> env, String cwd) {
            this.command = command;
            this.args = args != null ? args : List.of();
            this.env = env;
            this.cwd = cwd;
        }
        
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }
        public String getCwd() { return cwd; }
        public void setCwd(String cwd) { this.cwd = cwd; }
    }
    
    /**
     * HTTP MCP 服务器配置
     */
    public static class McpHttpServerConfig extends McpServerConfig {
        @JsonProperty("url")
        private String url;
        
        @JsonProperty("headers")
        private Map<String, String> headers = new HashMap<>();
        
        public McpHttpServerConfig() {}
        
        public McpHttpServerConfig(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers != null ? headers : new HashMap<>();
        }
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    }
    
    /**
     * WebSocket MCP 服务器配置
     */
    public static class McpWebSocketServerConfig extends McpServerConfig {
        @JsonProperty("url")
        private String url;
        
        @JsonProperty("headers")
        private Map<String, String> headers = new HashMap<>();
        
        public McpWebSocketServerConfig() {}
        
        public McpWebSocketServerConfig(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers != null ? headers : new HashMap<>();
        }
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    }
}
