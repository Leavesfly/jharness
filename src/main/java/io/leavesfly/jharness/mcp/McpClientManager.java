package io.leavesfly.jharness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jharness.mcp.types.McpConnectionStatus;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 客户端管理器
 *
 * 管理 MCP 服务器的连接和工具发现。
 * 支持 stdio 和 HTTP 传输方式。
 */
public class McpClientManager {
    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    private final Map<String, Map<String, Object>> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, McpConnectionStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);
    private final OkHttpClient sharedHttpClient;

    public McpClientManager() {
        this.sharedHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    /**
     * 添加服务器配置
     */
    public void addServer(String name, Map<String, Object> config) {
        serverConfigs.put(name, config);
        statuses.put(name, new McpConnectionStatus("pending", 
            (String) config.get("type"), null));
        logger.info("添加 MCP 服务器: {}", name);
    }
    
    /**
     * 连接所有配置的服务器
     */
    public CompletableFuture<Void> connectAll() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : serverConfigs.entrySet()) {
            futures.add(connectServer(entry.getKey(), entry.getValue()));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * 连接单个服务器
     */
    public CompletableFuture<Void> connectServer(String name, Map<String, Object> config) {
        return CompletableFuture.runAsync(() -> {
            try {
                String type = (String) config.get("type");
                if ("stdio".equals(type)) {
                    connectStdio(name, config);
                } else if ("http".equals(type) || "https".equals(type)) {
                    connectHttp(name, config);
                } else {
                    statuses.put(name, new McpConnectionStatus("failed", type, 
                        "不支持的传输类型: " + type));
                }
            } catch (Exception e) {
                logger.error("连接 MCP 服务器失败: {}", name, e);
                statuses.put(name, new McpConnectionStatus("failed", 
                    (String) config.get("type"), e.getMessage()));
            }
        }, executor);
    }
    
    /**
     * 连接 stdio 类型的 MCP 服务器
     */
    private void connectStdio(String name, Map<String, Object> config) throws Exception {
        String command = (String) config.get("command");
        List<String> args = (List<String>) config.getOrDefault("args", Collections.emptyList());
        Map<String, String> env = (Map<String, String>) config.get("env");
        String cwd = (String) config.get("cwd");
        
        // 构建进程
        ProcessBuilder pb = new ProcessBuilder();
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);
        pb.command(cmd);
        
        if (env != null) {
            pb.environment().putAll(env);
        }
        if (cwd != null) {
            pb.directory(new java.io.File(cwd));
        }
        
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        try {
            // 创建会话
            McpSession session = new StdioMcpSession(process, name);
            session.initialize();
            
            // 发现工具和资源
            List<McpConnectionStatus.McpToolInfo> tools = session.listTools();
            List<McpConnectionStatus.McpResourceInfo> resources = session.listResources();
            
            sessions.put(name, session);
            statuses.put(name, new McpConnectionStatus("connected", "stdio", null,
                env != null && !env.isEmpty(), tools, resources));
            
            logger.info("MCP 服务器已连接: {}, 工具数: {}", name, tools.size());
        } catch (Exception e) {
            // 初始化失败时清理进程，防止泄漏
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            throw e;
        }
    }
    
    /**
     * 连接 HTTP 类型的 MCP 服务器
     */
    @SuppressWarnings("unchecked")
    private void connectHttp(String name, Map<String, Object> config) throws Exception {
        String url = (String) config.get("url");
        Map<String, String> headers = (Map<String, String>) config.get("headers");

        HttpMcpSession session = new HttpMcpSession(sharedHttpClient, url, headers, name);
        session.initialize();

        List<McpConnectionStatus.McpToolInfo> tools = session.listTools();
        List<McpConnectionStatus.McpResourceInfo> resources = session.listResources();

        sessions.put(name, session);
        statuses.put(name, new McpConnectionStatus("connected", "http", null,
                headers != null && !headers.isEmpty(), tools, resources));

        logger.info("MCP HTTP 服务器已连接: {}, 工具数: {}", name, tools.size());
    }
    
    /**
     * 列出所有工具
     */
    public List<McpConnectionStatus.McpToolInfo> listTools() {
        List<McpConnectionStatus.McpToolInfo> allTools = new ArrayList<>();
        for (McpConnectionStatus status : statuses.values()) {
            if ("connected".equals(status.getState())) {
                allTools.addAll(status.getTools());
            }
        }
        return allTools;
    }
    
    /**
     * 列出所有资源
     */
    public List<McpConnectionStatus.McpResourceInfo> listResources() {
        List<McpConnectionStatus.McpResourceInfo> allResources = new ArrayList<>();
        for (McpConnectionStatus status : statuses.values()) {
            if ("connected".equals(status.getState())) {
                allResources.addAll(status.getResources());
            }
        }
        return allResources;
    }
    
    /**
     * 调用 MCP 工具
     */
    public CompletableFuture<String> callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpSession session = sessions.get(serverName);
        if (session == null) {
            return CompletableFuture.completedFuture("错误: 服务器未连接: " + serverName);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return session.callTool(toolName, arguments);
            } catch (Exception e) {
                logger.error("工具调用失败: {}.{}", serverName, toolName, e);
                return "工具调用失败: " + e.getMessage();
            }
        }, executor);
    }
    
    /**
     * 读取 MCP 资源
     */
    public CompletableFuture<String> readResource(String serverName, String uri) {
        McpSession session = sessions.get(serverName);
        if (session == null) {
            return CompletableFuture.completedFuture("错误: 服务器未连接: " + serverName);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return session.readResource(uri);
            } catch (Exception e) {
                logger.error("资源读取失败: {}:{}", serverName, uri, e);
                return "资源读取失败: " + e.getMessage();
            }
        }, executor);
    }
    
    /**
     * 获取连接状态
     */
    public McpConnectionStatus getStatus(String serverName) {
        return statuses.getOrDefault(serverName, 
            new McpConnectionStatus("disconnected", null, null));
    }
    
    /**
     * 列出所有服务器
     */
    public List<String> listServers() {
        return new ArrayList<>(serverConfigs.keySet());
    }
    
    /**
     * 列出所有连接状态
     */
    public List<McpConnectionStatus> listStatuses() {
        List<McpConnectionStatus> result = new ArrayList<>();
        for (String name : serverConfigs.keySet().stream().sorted().toList()) {
            result.add(statuses.get(name));
        }
        return result;
    }
    
    /**
     * 重新连接所有服务器
     */
    public CompletableFuture<Void> reconnectAll() {
        return CompletableFuture.runAsync(() -> {
            close();
            for (Map.Entry<String, Map<String, Object>> entry : serverConfigs.entrySet()) {
                try {
                    connectServer(entry.getKey(), entry.getValue()).get();
                } catch (Exception e) {
                    logger.error("重新连接失败: {}", entry.getKey(), e);
                }
            }
        }, executor);
    }
    
    /**
     * 更新服务器配置
     */
    public void updateServerConfig(String name, Map<String, Object> config) {
        serverConfigs.put(name, config);
    }
    
    /**
     * 关闭所有连接
     */
    public void close() {
        for (McpSession session : sessions.values()) {
            try {
                session.close();
            } catch (Exception e) {
                logger.error("关闭会话失败", e);
            }
        }
        sessions.clear();
        statuses.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * MCP 会话接口
     */
    private interface McpSession {
        void initialize() throws Exception;
        List<McpConnectionStatus.McpToolInfo> listTools() throws Exception;
        List<McpConnectionStatus.McpResourceInfo> listResources() throws Exception;
        String callTool(String toolName, Map<String, Object> arguments) throws Exception;
        String readResource(String uri) throws Exception;
        void close() throws Exception;
    }
    
    /**
     * stdio MCP 会话实现
     */
    private class StdioMcpSession implements McpSession {
        private final Process process;
        private final String serverName;
        private final BufferedReader reader;
        private final java.io.OutputStream writer;
        
        StdioMcpSession(Process process, String serverName) {
            this.process = process;
            this.serverName = serverName;
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.writer = process.getOutputStream();
        }
        
        @Override
        public void initialize() throws Exception {
            // 发送 initialize 请求
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", requestIdCounter.incrementAndGet());
            request.put("method", "initialize");
            
            ObjectNode params = MAPPER.createObjectNode();
            ObjectNode caps = MAPPER.createObjectNode();
            caps.set("roots", MAPPER.createObjectNode());
            caps.set("sampling", MAPPER.createObjectNode());
            params.set("capabilities", caps);
            params.put("protocolVersion", "2024-11-05");
            
            ObjectNode clientInfo = MAPPER.createObjectNode();
            clientInfo.put("name", "JHarness");
            clientInfo.put("version", "0.1.0");
            params.set("clientInfo", clientInfo);
            
            request.set("params", params);
            
            sendRequest(request);
            
            // 读取响应
            String response = readResponse();
            logger.debug("Initialize 响应: {}", response);
            
            // 发送 initialized 通知
            ObjectNode initialized = MAPPER.createObjectNode();
            initialized.put("jsonrpc", "2.0");
            initialized.put("method", "notifications/initialized");
            sendRequest(initialized);
        }
        
        @Override
        public List<McpConnectionStatus.McpToolInfo> listTools() throws Exception {
            ObjectNode request = createJsonRpcRequest("tools/list");
            sendRequest(request);
            String response = readResponse();
            
            JsonNode json = MAPPER.readTree(response);
            JsonNode toolsNode = json.path("result").path("tools");
            
            List<McpConnectionStatus.McpToolInfo> tools = new ArrayList<>();
            if (toolsNode.isArray()) {
                for (JsonNode tool : toolsNode) {
                    String name = tool.path("name").asText();
                    String description = tool.path("description").asText("");
                    Map<String, Object> inputSchema = MAPPER.convertValue(
                        tool.path("inputSchema"), Map.class);
                    
                    tools.add(new McpConnectionStatus.McpToolInfo(
                        serverName, name, description, inputSchema));
                }
            }
            return tools;
        }
        
        @Override
        public List<McpConnectionStatus.McpResourceInfo> listResources() throws Exception {
            ObjectNode request = createJsonRpcRequest("resources/list");
            sendRequest(request);
            String response = readResponse();
            
            JsonNode json = MAPPER.readTree(response);
            JsonNode resourcesNode = json.path("result").path("resources");
            
            List<McpConnectionStatus.McpResourceInfo> resources = new ArrayList<>();
            if (resourcesNode.isArray()) {
                for (JsonNode resource : resourcesNode) {
                    String uri = resource.path("uri").asText();
                    String name = resource.path("name").asText(uri);
                    String description = resource.path("description").asText("");
                    
                    resources.add(new McpConnectionStatus.McpResourceInfo(
                        serverName, name, uri, description));
                }
            }
            return resources;
        }
        
        @Override
        public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
            ObjectNode request = createJsonRpcRequest("tools/call");
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", MAPPER.valueToTree(arguments));
            request.set("params", params);
            
            sendRequest(request);
            String response = readResponse();
            
            JsonNode json = MAPPER.readTree(response);
            JsonNode contentNode = json.path("result").path("content");
            
            List<String> parts = new ArrayList<>();
            if (contentNode.isArray()) {
                for (JsonNode content : contentNode) {
                    if ("text".equals(content.path("type").asText())) {
                        parts.add(content.path("text").asText());
                    } else {
                        parts.add(content.toString());
                    }
                }
            }
            
            if (parts.isEmpty()) {
                return "(无输出)";
            }
            return String.join("\n", parts);
        }
        
        @Override
        public String readResource(String uri) throws Exception {
            ObjectNode request = createJsonRpcRequest("resources/read");
            ObjectNode params = MAPPER.createObjectNode();
            params.put("uri", uri);
            request.set("params", params);
            
            sendRequest(request);
            String response = readResponse();
            
            JsonNode json = MAPPER.readTree(response);
            JsonNode contentsNode = json.path("result").path("contents");
            
            List<String> parts = new ArrayList<>();
            if (contentsNode.isArray()) {
                for (JsonNode content : contentsNode) {
                    if (content.has("text")) {
                        parts.add(content.path("text").asText());
                    } else if (content.has("blob")) {
                        parts.add(content.path("blob").asText());
                    }
                }
            }
            
            if (parts.isEmpty()) {
                return "(无内容)";
            }
            return String.join("\n", parts);
        }
        
        @Override
        public void close() throws Exception {
            if (process.isAlive()) {
                process.destroy();
            }
        }
        
        private ObjectNode createJsonRpcRequest(String method) {
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", requestIdCounter.incrementAndGet());
            request.put("method", method);
            return request;
        }
        
        private void sendRequest(ObjectNode request) throws IOException {
            String json = MAPPER.writeValueAsString(request) + "\n";
            writer.write(json.getBytes(StandardCharsets.UTF_8));
            writer.flush();
        }
        
        /**
         * 读取 JSON-RPC 响应
         *
         * 逐行读取，累积 JSON 内容，通过大括号平衡检测完整的 JSON 对象。
         */
        private String readResponse() throws IOException {
            StringBuilder sb = new StringBuilder();
            int braceDepth = 0;
            boolean started = false;

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                sb.append(line);

                for (char ch : trimmed.toCharArray()) {
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

    /**
     * HTTP MCP 会话实现
     *
     * 通过 HTTP POST 发送 JSON-RPC 请求到 MCP 服务器。
     */
    private class HttpMcpSession implements McpSession {
        private final OkHttpClient httpClient;
        private final String baseUrl;
        private final Map<String, String> headers;
        private final String serverName;

        HttpMcpSession(OkHttpClient httpClient, String baseUrl,
                       Map<String, String> headers, String serverName) {
            this.httpClient = httpClient;
            this.baseUrl = baseUrl;
            this.headers = headers != null ? headers : Collections.emptyMap();
            this.serverName = serverName;
        }

        @Override
        public void initialize() throws Exception {
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", requestIdCounter.incrementAndGet());
            request.put("method", "initialize");

            ObjectNode params = MAPPER.createObjectNode();
            ObjectNode caps = MAPPER.createObjectNode();
            caps.set("roots", MAPPER.createObjectNode());
            params.set("capabilities", caps);
            params.put("protocolVersion", "2024-11-05");

            ObjectNode clientInfo = MAPPER.createObjectNode();
            clientInfo.put("name", "JHarness");
            clientInfo.put("version", "0.1.0");
            params.set("clientInfo", clientInfo);
            request.set("params", params);

            String response = sendHttpRequest(request);
            logger.debug("HTTP Initialize 响应: {}", response);

            ObjectNode initialized = MAPPER.createObjectNode();
            initialized.put("jsonrpc", "2.0");
            initialized.put("method", "notifications/initialized");
            sendHttpRequest(initialized);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<McpConnectionStatus.McpToolInfo> listTools() throws Exception {
            ObjectNode request = createJsonRpcRequest("tools/list");
            String response = sendHttpRequest(request);

            JsonNode json = MAPPER.readTree(response);
            JsonNode toolsNode = json.path("result").path("tools");

            List<McpConnectionStatus.McpToolInfo> tools = new ArrayList<>();
            if (toolsNode.isArray()) {
                for (JsonNode tool : toolsNode) {
                    String name = tool.path("name").asText();
                    String description = tool.path("description").asText("");
                    Map<String, Object> inputSchema = MAPPER.convertValue(
                            tool.path("inputSchema"), Map.class);
                    tools.add(new McpConnectionStatus.McpToolInfo(
                            serverName, name, description, inputSchema));
                }
            }
            return tools;
        }

        @Override
        public List<McpConnectionStatus.McpResourceInfo> listResources() throws Exception {
            ObjectNode request = createJsonRpcRequest("resources/list");
            String response = sendHttpRequest(request);

            JsonNode json = MAPPER.readTree(response);
            JsonNode resourcesNode = json.path("result").path("resources");

            List<McpConnectionStatus.McpResourceInfo> resources = new ArrayList<>();
            if (resourcesNode.isArray()) {
                for (JsonNode resource : resourcesNode) {
                    String uri = resource.path("uri").asText();
                    String name = resource.path("name").asText(uri);
                    String description = resource.path("description").asText("");
                    resources.add(new McpConnectionStatus.McpResourceInfo(
                            serverName, name, uri, description));
                }
            }
            return resources;
        }

        @Override
        public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
            ObjectNode request = createJsonRpcRequest("tools/call");
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", MAPPER.valueToTree(arguments));
            request.set("params", params);

            String response = sendHttpRequest(request);
            JsonNode json = MAPPER.readTree(response);
            JsonNode contentNode = json.path("result").path("content");

            List<String> parts = new ArrayList<>();
            if (contentNode.isArray()) {
                for (JsonNode content : contentNode) {
                    if ("text".equals(content.path("type").asText())) {
                        parts.add(content.path("text").asText());
                    } else {
                        parts.add(content.toString());
                    }
                }
            }
            return parts.isEmpty() ? "(无输出)" : String.join("\n", parts);
        }

        @Override
        public String readResource(String uri) throws Exception {
            ObjectNode request = createJsonRpcRequest("resources/read");
            ObjectNode params = MAPPER.createObjectNode();
            params.put("uri", uri);
            request.set("params", params);

            String response = sendHttpRequest(request);
            JsonNode json = MAPPER.readTree(response);
            JsonNode contentsNode = json.path("result").path("contents");

            List<String> parts = new ArrayList<>();
            if (contentsNode.isArray()) {
                for (JsonNode content : contentsNode) {
                    if (content.has("text")) {
                        parts.add(content.path("text").asText());
                    } else if (content.has("blob")) {
                        parts.add(content.path("blob").asText());
                    }
                }
            }
            return parts.isEmpty() ? "(无内容)" : String.join("\n", parts);
        }

        @Override
        public void close() {
            // HTTP 连接无需显式关闭
        }

        private ObjectNode createJsonRpcRequest(String method) {
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", requestIdCounter.incrementAndGet());
            request.put("method", method);
            return request;
        }

        private String sendHttpRequest(ObjectNode jsonRpcRequest) throws IOException {
            String body = MAPPER.writeValueAsString(jsonRpcRequest);

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
                return responseBody != null ? responseBody.string() : "{}";
            }
        }
    }
}
