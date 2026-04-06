package io.leavesfly.jharness.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jharness.api.errors.AuthenticationFailureException;
import io.leavesfly.jharness.api.errors.OpenHarnessApiException;
import io.leavesfly.jharness.api.errors.RateLimitFailureException;
import io.leavesfly.jharness.api.retry.RetryPolicy;
import io.leavesfly.jharness.engine.model.ContentBlock;
import io.leavesfly.jharness.engine.model.ConversationMessage;
import io.leavesfly.jharness.engine.model.TextBlock;
import io.leavesfly.jharness.engine.model.ToolResultBlock;
import io.leavesfly.jharness.engine.model.ToolUseBlock;
import io.leavesfly.jharness.engine.model.UsageSnapshot;
import io.leavesfly.jharness.engine.stream.AssistantTextDelta;
import io.leavesfly.jharness.engine.stream.AssistantTurnComplete;
import io.leavesfly.jharness.engine.stream.StreamEvent;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OpenAI 标准协议 API 客户端
 *
 * 使用 OkHttp 实现与 OpenAI 兼容 API 的交互，支持流式响应和重试机制。
 * 兼容所有 OpenAI 标准协议的 API 端点（如 DashScope、DeepSeek、Moonshot、vLLM 等）。
 */
public class OpenAiApiClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(OpenAiApiClient.class);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";
    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final int READ_TIMEOUT_SECONDS = 300;
    private static final int WRITE_TIMEOUT_SECONDS = 30;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String completionsUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final RetryPolicy retryPolicy;

    /**
     * 构造 API 客户端
     *
     * @param baseUrl   API 基础 URL（为 null 时使用 OpenAI 默认地址）
     * @param apiKey    API 密钥
     * @param model     模型名称
     * @param maxTokens 最大输出 token 数
     */
    public OpenAiApiClient(String baseUrl, String apiKey, String model, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空，请设置环境变量 OPENAI_API_KEY 或 ANTHROPIC_API_KEY");
        }
        this.completionsUrl = buildCompletionsUrl(baseUrl != null ? baseUrl : "https://api.openai.com");
        logger.info("API 请求地址: {}", completionsUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.retryPolicy = RetryPolicy.defaultPolicy();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();

        this.objectMapper = new ObjectMapper();
    }

    /**
     * 关闭客户端，释放连接池和线程池资源
     */
    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
        if (httpClient.cache() != null) {
            try {
                httpClient.cache().close();
            } catch (Exception e) {
                logger.warn("关闭 HTTP 缓存失败", e);
            }
        }
    }

    /**
     * 流式发送消息（带重试）
     *
     * @param messages      消息列表
     * @param systemPrompt  系统提示词
     * @param tools         工具定义列表
     * @param eventConsumer 事件消费者
     * @return 包含完整消息和使用量的 CompletableFuture
     */
    public CompletableFuture<ApiMessageCompleteEvent> streamMessage(
            List<ConversationMessage> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            Consumer<StreamEvent> eventConsumer) {

        return streamMessageWithRetry(messages, systemPrompt, tools, eventConsumer, 0);
    }

    private CompletableFuture<ApiMessageCompleteEvent> streamMessageWithRetry(
            List<ConversationMessage> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            Consumer<StreamEvent> eventConsumer,
            int attempt) {

        CompletableFuture<ApiMessageCompleteEvent> future = new CompletableFuture<>();

        try {
            ObjectNode requestBody = buildRequestBody(messages, systemPrompt, tools);

            Request request = new Request.Builder()
                    .url(completionsUrl)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(
                            objectMapper.writeValueAsString(requestBody),
                            MediaType.parse("application/json")))
                    .build();

            SseResponseCollector collector = new SseResponseCollector(objectMapper, eventConsumer);

            EventSourceListener listener = new EventSourceListener() {
                @Override
                public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
                    logger.debug("SSE 连接已打开, status={}", response.code());
                }

                @Override
                public void onEvent(@NotNull EventSource eventSource,
                                    @Nullable String id,
                                    @Nullable String type,
                                    @NotNull String data) {
                    try {
                        collector.handleEvent(data);
                    } catch (Exception e) {
                        logger.error("处理 SSE 事件时出错", e);
                    }
                }

                @Override
                public void onClosed(@NotNull EventSource eventSource) {
                    logger.debug("SSE 连接已关闭");
                    collector.finalizeCollection();
                    future.complete(collector.buildCompleteEvent());
                }

                @Override
                public void onFailure(@NotNull EventSource eventSource,
                                      @Nullable Throwable throwable,
                                      @Nullable Response response) {
                    OpenHarnessApiException apiException = classifyError(throwable, response);
                    logger.error("SSE 连接失败: {}", apiException.getMessage());

                    if (retryPolicy.shouldRetry(attempt, apiException)) {
                        long delayMs = retryPolicy.getDelayMs(attempt);
                        logger.info("将在 {}ms 后重试 (第 {} 次)", delayMs, attempt + 1);
                        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                                .execute(() -> streamMessageWithRetry(
                                        messages, systemPrompt, tools, eventConsumer, attempt + 1)
                                        .whenComplete((result, error) -> {
                                            if (error != null) {
                                                future.completeExceptionally(error);
                                            } else {
                                                future.complete(result);
                                            }
                                        }));
                    } else {
                        future.completeExceptionally(apiException);
                    }
                }
            };

            EventSources.createFactory(httpClient).newEventSource(request, listener);

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    private OpenHarnessApiException classifyError(@Nullable Throwable throwable, @Nullable Response response) {
        if (response != null) {
            int code = response.code();
            String message = "API 错误 (HTTP " + code + ")";
            if (code == 401) {
                return new AuthenticationFailureException(message);
            } else if (code == 429) {
                return new RateLimitFailureException(message);
            }
            return new OpenHarnessApiException(message, code);
        }
        if (throwable != null) {
            return new OpenHarnessApiException("SSE 连接失败: " + throwable.getMessage(), 500, throwable);
        }
        return new OpenHarnessApiException("未知错误", 500);
    }

    /**
     * 根据 baseUrl 构建完整的 chat/completions 请求地址。
     * 兼容以下几种 baseUrl 格式：
     * - https://api.openai.com           → https://api.openai.com/v1/chat/completions
     * - https://api.openai.com/v1        → https://api.openai.com/v1/chat/completions
     * - https://api.openai.com/v1/       → https://api.openai.com/v1/chat/completions
     * - https://xxx.com/compatible-mode/v1 → https://xxx.com/compatible-mode/v1/chat/completions
     */
    private static String buildCompletionsUrl(String baseUrl) {
        String url = baseUrl.replaceAll("/+$", "");
        if (url.endsWith("/v1")) {
            return url + CHAT_COMPLETIONS_SUFFIX;
        }
        return url + CHAT_COMPLETIONS_PATH;
    }

    /**
     * SSE 响应收集器
     *
     * 解析 OpenAI 标准的流式 SSE 响应格式：
     * data: {"choices":[{"delta":{"content":"..."},"finish_reason":null}]}
     * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_xxx","function":{"name":"...","arguments":"..."}}]}}]}
     * data: [DONE]
     */
    private static class SseResponseCollector {
        private final ObjectMapper objectMapper;
        private final Consumer<StreamEvent> eventConsumer;

        private final StringBuilder fullText = new StringBuilder();
        private final List<ToolCallAccumulator> toolCallAccumulators = new ArrayList<>();

        private int promptTokens;
        private int completionTokens;

        SseResponseCollector(ObjectMapper objectMapper, Consumer<StreamEvent> eventConsumer) {
            this.objectMapper = objectMapper;
            this.eventConsumer = eventConsumer;
        }

        void handleEvent(String data) throws Exception {
            if ("[DONE]".equals(data)) {
                return;
            }

            JsonNode json = objectMapper.readTree(data);

            // 解析 usage（部分 API 在最后一个 chunk 中返回 usage）
            JsonNode usageNode = json.path("usage");
            if (!usageNode.isMissingNode()) {
                promptTokens = usageNode.path("prompt_tokens").asInt(0);
                completionTokens = usageNode.path("completion_tokens").asInt(0);
            }

            // 解析 error
            JsonNode errorNode = json.path("error");
            if (!errorNode.isMissingNode() && errorNode.isObject()) {
                String errorMessage = errorNode.path("message").asText("未知服务端错误");
                String errorType = errorNode.path("type").asText("unknown");
                logger.error("收到 SSE 错误事件: type={}, message={}", errorType, errorMessage);
                throw new RuntimeException("API 错误 (" + errorType + "): " + errorMessage);
            }

            JsonNode choices = json.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return;
            }

            JsonNode firstChoice = choices.get(0);
            JsonNode delta = firstChoice.path("delta");
            String finishReason = firstChoice.path("finish_reason").asText(null);

            // 处理文本内容增量
            String contentDelta = delta.path("content").asText(null);
            if (contentDelta != null) {
                fullText.append(contentDelta);
                eventConsumer.accept(new AssistantTextDelta(contentDelta));
            }

            // 处理 tool_calls 增量
            JsonNode toolCallsNode = delta.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode toolCallDelta : toolCallsNode) {
                    int index = toolCallDelta.path("index").asInt(0);
                    handleToolCallDelta(index, toolCallDelta);
                }
            }

            // 处理完成信号
            if ("stop".equals(finishReason) || "tool_calls".equals(finishReason)) {
                eventConsumer.accept(new AssistantTurnComplete("消息完成"));
            }
        }

        private void handleToolCallDelta(int index, JsonNode toolCallDelta) {
            while (toolCallAccumulators.size() <= index) {
                toolCallAccumulators.add(new ToolCallAccumulator());
            }
            ToolCallAccumulator accumulator = toolCallAccumulators.get(index);

            String callId = toolCallDelta.path("id").asText(null);
            if (callId != null) {
                accumulator.id = callId;
            }

            JsonNode functionNode = toolCallDelta.path("function");
            if (!functionNode.isMissingNode()) {
                String name = functionNode.path("name").asText(null);
                if (name != null) {
                    accumulator.name = name;
                    logger.debug("开始收集工具调用: name={}, id={}", name, accumulator.id);
                }
                String argumentsDelta = functionNode.path("arguments").asText(null);
                if (argumentsDelta != null) {
                    accumulator.argumentsBuilder.append(argumentsDelta);
                }
            }
        }

        void finalizeCollection() {
            // 不做额外操作，所有数据已在 handleEvent 中收集
        }

        List<ToolUseBlock> buildToolUses() {
            List<ToolUseBlock> toolUses = new ArrayList<>();
            for (ToolCallAccumulator accumulator : toolCallAccumulators) {
                if (accumulator.id != null && accumulator.name != null) {
                    try {
                        String argsStr = accumulator.argumentsBuilder.toString();
                        JsonNode inputNode = argsStr.isEmpty()
                                ? objectMapper.createObjectNode()
                                : objectMapper.readTree(argsStr);
                        toolUses.add(new ToolUseBlock(accumulator.id, accumulator.name, inputNode));
                        logger.debug("收集到工具调用: name={}, id={}", accumulator.name, accumulator.id);
                    } catch (Exception e) {
                        logger.error("解析工具参数 JSON 失败: {}", accumulator.argumentsBuilder, e);
                    }
                }
            }
            return toolUses;
        }

        ApiMessageCompleteEvent buildCompleteEvent() {
            List<ToolUseBlock> toolUses = buildToolUses();
            UsageSnapshot usage = new UsageSnapshot(promptTokens, completionTokens, 0, 0);
            return new ApiMessageCompleteEvent(fullText.toString(), toolUses, usage);
        }
    }

    /**
     * 工具调用增量累加器，用于在流式响应中逐步拼接 tool_call 的各个部分
     */
    private static class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder argumentsBuilder = new StringBuilder();
    }

    /**
     * 构建 OpenAI 标准格式的请求体
     */
    @SuppressWarnings("unchecked")
    private ObjectNode buildRequestBody(List<ConversationMessage> messages, String systemPrompt,
                                        List<Map<String, Object>> tools) {
        ObjectNode body = objectMapper.createObjectNode();

        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        // 请求在最后一个 chunk 中返回 usage 信息
        ObjectNode streamOptions = body.putObject("stream_options");
        streamOptions.put("include_usage", true);

        ArrayNode messagesArray = body.putArray("messages");

        // system prompt 作为 messages 数组中的第一条 system 消息
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode systemMsg = messagesArray.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
        }

        // 转换对话消息
        for (ConversationMessage msg : messages) {
            convertMessage(msg, messagesArray);
        }

        // 添加工具定义（OpenAI 格式）
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (Map<String, Object> tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", (String) tool.get("name"));
                functionNode.put("description", (String) tool.get("description"));
                Object inputSchema = tool.get("input_schema");
                if (inputSchema != null) {
                    functionNode.set("parameters", objectMapper.valueToTree(inputSchema));
                }
            }
        }

        return body;
    }

    /**
     * 将内部 ConversationMessage 转换为 OpenAI 格式的消息
     *
     * OpenAI 格式中：
     * - assistant 消息如果有 tool_calls，则 tool_calls 是一个数组
     * - tool 结果是独立的 role=tool 消息，每个对应一个 tool_call_id
     */
    private void convertMessage(ConversationMessage msg, ArrayNode messagesArray) {
        String role = msg.getRole().name().toLowerCase();
        List<ContentBlock> blocks = msg.getContent();

        if ("user".equals(role)) {
            convertUserMessage(blocks, messagesArray);
        } else if ("assistant".equals(role)) {
            convertAssistantMessage(blocks, messagesArray);
        }
    }

    private void convertUserMessage(List<ContentBlock> blocks, ArrayNode messagesArray) {
        // 用户消息中可能混合 TextBlock 和 ToolResultBlock
        // ToolResultBlock 在 OpenAI 中需要作为独立的 role=tool 消息发送
        StringBuilder textContent = new StringBuilder();
        List<ToolResultBlock> toolResults = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                textContent.append(textBlock.getText());
            } else if (block instanceof ToolResultBlock toolResult) {
                toolResults.add(toolResult);
            }
        }

        // 先发送 tool 结果消息（必须紧跟在 assistant 的 tool_calls 之后）
        for (ToolResultBlock toolResult : toolResults) {
            ObjectNode toolMsg = messagesArray.addObject();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolResult.getToolUseId());
            toolMsg.put("content", toolResult.getContent());
        }

        // 再发送用户文本消息（如果有）
        if (!textContent.isEmpty()) {
            ObjectNode userMsg = messagesArray.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", textContent.toString());
        }
    }

    private void convertAssistantMessage(List<ContentBlock> blocks, ArrayNode messagesArray) {
        ObjectNode assistantMsg = messagesArray.addObject();
        assistantMsg.put("role", "assistant");

        StringBuilder textContent = new StringBuilder();
        List<ToolUseBlock> toolUses = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                textContent.append(textBlock.getText());
            } else if (block instanceof ToolUseBlock toolUse) {
                toolUses.add(toolUse);
            }
        }

        if (!textContent.isEmpty()) {
            assistantMsg.put("content", textContent.toString());
        } else {
            assistantMsg.putNull("content");
        }

        // 添加 tool_calls 数组
        if (!toolUses.isEmpty()) {
            ArrayNode toolCallsArray = assistantMsg.putArray("tool_calls");
            for (ToolUseBlock toolUse : toolUses) {
                ObjectNode toolCallNode = toolCallsArray.addObject();
                toolCallNode.put("id", toolUse.getId());
                toolCallNode.put("type", "function");
                ObjectNode functionNode = toolCallNode.putObject("function");
                functionNode.put("name", toolUse.getName());
                functionNode.put("arguments", toolUse.getInput().toString());
            }
        }
    }
}
