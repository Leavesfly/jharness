package io.leavesfly.jharness.tools.input;

/**
 * McpAuthTool 的输入模型
 * 
 * 用于配置 MCP 服务器的认证信息。
 */
public class McpAuthToolInput {
    private String serverName;
    private String mode;  // "bearer", "header", "env"
    private String value;
    private String key;   // 可选：自定义 header key 或环境变量名

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
