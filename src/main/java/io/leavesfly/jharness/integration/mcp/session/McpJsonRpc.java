package io.leavesfly.jharness.integration.mcp.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jharness.integration.mcp.types.McpConnectionStatus;
import io.leavesfly.jharness.util.JacksonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP JSON-RPC 协议公共工具：
 *   - 构建 initialize / generic 请求；
 *   - 解析 tools/list、resources/list、tools/call、resources/read 的响应。
 *
 * 抽出此工具类后，{@link StdioMcpSession} 与 {@link HttpMcpSession} 不再各自重复解析逻辑，
 * 协议升级时只需改一处。
 */
public final class McpJsonRpc {

    public static final ObjectMapper MAPPER = JacksonUtils.MAPPER;
    public static final String PROTOCOL_VERSION = "2024-11-05";
    public static final String CLIENT_NAME = "JHarness";
    public static final String CLIENT_VERSION = "0.1.0";

    private McpJsonRpc() {}

    public static ObjectNode buildInitializeRequest(AtomicInteger idCounter, boolean includeSampling) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", idCounter.incrementAndGet());
        request.put("method", "initialize");

        ObjectNode params = MAPPER.createObjectNode();
        ObjectNode caps = MAPPER.createObjectNode();
        caps.set("roots", MAPPER.createObjectNode());
        if (includeSampling) {
            caps.set("sampling", MAPPER.createObjectNode());
        }
        params.set("capabilities", caps);
        params.put("protocolVersion", PROTOCOL_VERSION);

        ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);
        params.set("clientInfo", clientInfo);

        request.set("params", params);
        return request;
    }

    public static ObjectNode buildInitializedNotification() {
        ObjectNode initialized = MAPPER.createObjectNode();
        initialized.put("jsonrpc", "2.0");
        initialized.put("method", "notifications/initialized");
        return initialized;
    }

    public static ObjectNode newRequest(AtomicInteger idCounter, String method) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", idCounter.incrementAndGet());
        request.put("method", method);
        return request;
    }

    @SuppressWarnings("unchecked")
    public static List<McpConnectionStatus.McpToolInfo> parseToolsResult(String response, String serverName) throws Exception {
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

    public static List<McpConnectionStatus.McpResourceInfo> parseResourcesResult(String response, String serverName) throws Exception {
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

    public static String parseToolCallResult(String response) throws Exception {
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

    public static String parseResourceReadResult(String response) throws Exception {
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

    public static ObjectNode wrapToolCallParams(String toolName, Map<String, Object> arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", MAPPER.valueToTree(arguments));
        return params;
    }

    public static ObjectNode wrapResourceReadParams(String uri) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("uri", uri);
        return params;
    }
}
