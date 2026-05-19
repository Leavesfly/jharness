package io.leavesfly.jharness.integration.mcp.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jharness.integration.mcp.types.McpConnectionStatus;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP MCP 会话：通过 HTTP POST 发送 JSON-RPC 请求到 MCP 服务器。
 *
 * 响应体强制限制为 16 MB 上限，避免恶意/异常服务器返回超大响应打爆内存。
 */
public class HttpMcpSession implements McpSession {

    /** MCP HTTP 响应体上限：16 MB。 */
    private static final long MAX_RESPONSE_BODY_BYTES = 16L * 1024 * 1024;

    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final Map<String, String> headers;
    private final String serverName;
    private final AtomicInteger requestIdCounter;

    public HttpMcpSession(OkHttpClient httpClient, String baseUrl,
                          Map<String, String> headers, String serverName,
                          AtomicInteger requestIdCounter) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.headers = headers != null ? headers : Collections.emptyMap();
        this.serverName = serverName;
        this.requestIdCounter = requestIdCounter;
    }

    @Override
    public void initialize() throws Exception {
        ObjectNode request = McpJsonRpc.buildInitializeRequest(requestIdCounter, false);
        sendHttpRequest(request);
        sendHttpRequest(McpJsonRpc.buildInitializedNotification());
    }

    @Override
    public List<McpConnectionStatus.McpToolInfo> listTools() throws Exception {
        String response = sendHttpRequest(McpJsonRpc.newRequest(requestIdCounter, "tools/list"));
        return McpJsonRpc.parseToolsResult(response, serverName);
    }

    @Override
    public List<McpConnectionStatus.McpResourceInfo> listResources() throws Exception {
        String response = sendHttpRequest(McpJsonRpc.newRequest(requestIdCounter, "resources/list"));
        return McpJsonRpc.parseResourcesResult(response, serverName);
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        ObjectNode request = McpJsonRpc.newRequest(requestIdCounter, "tools/call");
        request.set("params", McpJsonRpc.wrapToolCallParams(toolName, arguments));
        return McpJsonRpc.parseToolCallResult(sendHttpRequest(request));
    }

    @Override
    public String readResource(String uri) throws Exception {
        ObjectNode request = McpJsonRpc.newRequest(requestIdCounter, "resources/read");
        request.set("params", McpJsonRpc.wrapResourceReadParams(uri));
        return McpJsonRpc.parseResourceReadResult(sendHttpRequest(request));
    }

    @Override
    public void close() {
        // HTTP 连接无需显式关闭，由共享 OkHttpClient 统一管理
    }

    private String sendHttpRequest(ObjectNode jsonRpcRequest) throws IOException {
        String body = McpJsonRpc.MAPPER.writeValueAsString(jsonRpcRequest);

        Request.Builder requestBuilder = new Request.Builder()
                .url(baseUrl)
                .post(RequestBody.create(body, MediaType.parse("application/json")));

        for (Map.Entry<String, String> header : headers.entrySet()) {
            requestBuilder.header(header.getKey(), header.getValue());
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("MCP HTTP 请求失败: " + response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return "{}";
            }
            long contentLength = responseBody.contentLength();
            if (contentLength > MAX_RESPONSE_BODY_BYTES) {
                throw new IOException("MCP HTTP 响应体超过上限 "
                        + MAX_RESPONSE_BODY_BYTES + " 字节: " + contentLength);
            }
            // contentLength 未知时，通过 source().request 限流读取
            okio.BufferedSource src = responseBody.source();
            src.request(MAX_RESPONSE_BODY_BYTES + 1);
            okio.Buffer buf = src.getBuffer();
            if (buf.size() > MAX_RESPONSE_BODY_BYTES) {
                throw new IOException("MCP HTTP 响应体超过上限: " + buf.size());
            }
            return buf.readString(StandardCharsets.UTF_8);
        }
    }
}
