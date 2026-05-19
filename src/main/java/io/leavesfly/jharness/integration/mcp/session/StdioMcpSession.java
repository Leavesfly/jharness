package io.leavesfly.jharness.integration.mcp.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jharness.integration.mcp.types.McpConnectionStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * stdio MCP 会话：把 JSON-RPC 请求写入子进程 stdin，再从 stdout 读取响应。
 *
 * <p>关键实现细节：
 * <ul>
 *   <li>{@link #readResponse()} 通过 ready() 轮询 + sleep，保证在
 *       {@link #READ_RESPONSE_TIMEOUT_SECONDS} 秒内无新数据时返回超时异常，避免
 *       reader.readLine() 永久阻塞；</li>
 *   <li>识别字符串字面量和转义字符，不再把字符串里的花括号当作 JSON 结构的一部分，
 *       防止响应包含 "{" / "}" 文本时误判；</li>
 *   <li>子进程退出时立即抛异常终止等待。</li>
 * </ul>
 */
public class StdioMcpSession implements McpSession {

    /** 单次 readResponse 的最大等待时间（秒）。 */
    private static final int READ_RESPONSE_TIMEOUT_SECONDS = 30;

    private final Process process;
    private final String serverName;
    private final BufferedReader reader;
    private final OutputStream writer;
    private final AtomicInteger requestIdCounter;

    public StdioMcpSession(Process process, String serverName, AtomicInteger requestIdCounter) {
        this.process = process;
        this.serverName = serverName;
        this.requestIdCounter = requestIdCounter;
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.writer = process.getOutputStream();
    }

    @Override
    public void initialize() throws Exception {
        ObjectNode request = McpJsonRpc.buildInitializeRequest(requestIdCounter, true);
        sendRequest(request);
        String response = readResponse();
        // 仅 debug 级别记录响应，避免污染主日志
        // (调用方关心的是连接是否建立成功，由后续 listTools/listResources 反映)
        sendRequest(McpJsonRpc.buildInitializedNotification());
    }

    @Override
    public List<McpConnectionStatus.McpToolInfo> listTools() throws Exception {
        sendRequest(McpJsonRpc.newRequest(requestIdCounter, "tools/list"));
        return McpJsonRpc.parseToolsResult(readResponse(), serverName);
    }

    @Override
    public List<McpConnectionStatus.McpResourceInfo> listResources() throws Exception {
        sendRequest(McpJsonRpc.newRequest(requestIdCounter, "resources/list"));
        return McpJsonRpc.parseResourcesResult(readResponse(), serverName);
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        ObjectNode request = McpJsonRpc.newRequest(requestIdCounter, "tools/call");
        request.set("params", McpJsonRpc.wrapToolCallParams(toolName, arguments));
        sendRequest(request);
        return McpJsonRpc.parseToolCallResult(readResponse());
    }

    @Override
    public String readResource(String uri) throws Exception {
        ObjectNode request = McpJsonRpc.newRequest(requestIdCounter, "resources/read");
        request.set("params", McpJsonRpc.wrapResourceReadParams(uri));
        sendRequest(request);
        return McpJsonRpc.parseResourceReadResult(readResponse());
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
        }
    }

    private void sendRequest(ObjectNode request) throws IOException {
        String json = McpJsonRpc.MAPPER.writeValueAsString(request) + "\n";
        writer.write(json.getBytes(StandardCharsets.UTF_8));
        writer.flush();
    }

    /** 读取一段完整的 JSON-RPC 响应（带超时、字符串/转义感知）。 */
    private String readResponse() throws IOException {
        StringBuilder sb = new StringBuilder();
        int braceDepth = 0;
        boolean started = false;
        boolean inString = false;
        boolean escaped = false;

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READ_RESPONSE_TIMEOUT_SECONDS);

        while (true) {
            if (System.nanoTime() > deadline) {
                throw new IOException("MCP 服务器响应超时（>"
                        + READ_RESPONSE_TIMEOUT_SECONDS + "s）: " + serverName);
            }
            if (!reader.ready()) {
                if (!process.isAlive()) {
                    throw new IOException("MCP 服务器进程已退出: " + serverName);
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("读取 MCP 响应被中断", ie);
                }
                continue;
            }

            String line = reader.readLine();
            if (line == null) {
                break;
            }
            if (!started && line.trim().isEmpty()) {
                continue;
            }

            sb.append(line);

            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (ch == '{') {
                    braceDepth++;
                    started = true;
                } else if (ch == '}') {
                    braceDepth--;
                }
            }

            if (started && braceDepth == 0) {
                break;
            }
        }

        if (sb.length() == 0) {
            throw new IOException("MCP 服务器未返回响应");
        }
        return sb.toString();
    }
}
