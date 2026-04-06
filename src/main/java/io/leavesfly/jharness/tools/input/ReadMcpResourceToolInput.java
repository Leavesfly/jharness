package io.leavesfly.jharness.tools.input;

/**
 * ReadMcpResourceTool 的输入模型
 * 
 * 用于读取特定 MCP 服务器的资源内容。
 */
public class ReadMcpResourceToolInput {
    private String server;
    private String uri;

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }
}
