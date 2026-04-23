package io.leavesfly.jharness.integration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jharness.integration.api.errors.AuthenticationFailureException;
import io.leavesfly.jharness.integration.api.errors.OpenHarnessApiException;
import io.leavesfly.jharness.integration.api.errors.RateLimitFailureException;
import io.leavesfly.jharness.integration.api.retry.RetryPolicy;
import io.leavesfly.jharness.core.engine.model.ContentBlock;
import io.leavesfly.jharness.core.engine.model.ConversationMessage;
import io.leavesfly.jharness.core.engine.model.ImageBlock;
import io.leavesfly.jharness.core.engine.model.TextBlock;
import io.leavesfly.jharness.core.engine.model.ToolResultBlock;
import io.leavesfly.jharness.core.engine.model.ToolUseBlock;
import io.leavesfly.jharness.core.engine.model.UsageSnapshot;
import io.leavesfly.jharness.core.engine.stream.AssistantTextDelta;
import io.leavesfly.jharness.core.engine.stream.AssistantTurnComplete;
import io.leavesfly.jharness.core.engine.stream.StreamEvent;
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
import java.util.Collections;
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
 *
 * F-P0-1：实现 {@link LlmProvider} 接口，统一 Provider 抽象。
 */
public class OpenAiApiClient implements LlmProvider {
    private static final Logger logger = LoggerFactory.getLogger(OpenAiApiClient.class);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";
    /** 默认超时常量（P2-M28）：保留为常量以便单元测试覆盖，实际可通过构造器覆盖。 */
    static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 30;
    static final int DEFAULT_READ_TIMEOUT_SECONDS = 300;
    static final int DEFAULT_WRITE_TIMEOUT_SECONDS = 30;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String completionsUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final RetryPolicy retryPolicy;
    private final List<EventSource> activeEventSources = Collections.synchronizedList(new ArrayList<>());
    /**
     * 当前活跃的 streamMessage future 列表，用于 {@link #cancelAllActiveRequests()} 能确保
     * 它们以 CancellationException 异常完成，避免上层 join() 因 SSE.cancel() 不保证触发
     * onClosed/onFailure 而永久阻塞。
     */
    private final List<CompletableFuture<ApiMessageCompleteEvent>> activeFutures =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * 构造 API 客户端（默认超时）。
     *
     * @param baseUrl   API 基础 URL（为 null 时使用 OpenAI 默认地址）
     * @param apiKey    API 密钥
     * @param model     模型名称
     * @param maxTokens 最大输出 token 数
     */
    public OpenAiApiClient(String baseUrl, String apiKey, String model, int maxTokens) {
        this(baseUrl, apiKey, model, maxTokens,
                DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_READ_TIMEOUT_SECONDS, DEFAULT_WRITE_TIMEOUT_SECONDS);
    }

    /**
     * 构造 API 客户端（自定义超时，P2-M28）。
     *
     * 暴露超时参数，方便调用方从 Settings / 环境变量注入，避免硬编码 300s 在慢连接下不够或快连接下浪费。
     */
    public OpenAiApiClient(String baseUrl, String apiKey, String model, int maxTokens,
                           int connectTimeoutSeconds, int readTimeoutSeconds, int writeTimeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空，请设置环境变量 OPENAI_API_KEY 或 ANTHROPIC_API_KEY");
        }
        if (connectTimeoutSeconds <= 0 || readTimeoutSeconds <= 0 || writeTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "超时参数必须为正数: connect=" + connectTimeoutSeconds
                            + ", read=" + readTimeoutSeconds
                            + ", write=" + writeTimeoutSeconds);
        }
        this.completionsUrl = buildCompletionsUrl(baseUrl != null ? baseUrl : "https://api.openai.com");
        logger.info("API 请求地址: {}, 超时(s) connect={}/read={}/write={}",
                completionsUrl, connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds);
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.retryPolicy = RetryPolicy.defaultPolicy();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
                .build();

        this.objectMapper = new ObjectMapper();
    }

    /**
     * 关闭客户端，释放连接池和线程池资源。
     *
     * 改进点（P1-C2）：
     * - 每个关闭步骤独立 try/catch，任何一步失败都不影响后续资源释放；
     * - 即使 SSE 取消出现异常，也必须 clear 活跃列表，防止内存泄漏；
     * - dispatcher.executorService().shutdown() 与 connectionPool.evictAll() 分别处理异常。
     */
    @Override
    public void close() {
        // 1. 取消所有活跃的 SSE 连接
        try {
            synchronized (activeEventSources) {
                for (EventSource es : activeEventSources) {
                    try {
                        es.cancel();
                    } catch (Exception e) {
                        logger.warn("取消 SSE 连接失败", e);
                    }
                }
                activeEventSources.clear();
            }
        } catch (Exception e) {
            logger.warn("清理活跃 SSE 列表失败", e);
        }

        // 2. 关闭 dispatcher 线程池：shutdown + 短暂等待 + shutdownNow 三段式
        try {
            java.util.concurrent.ExecutorService executor = httpClient.dispatcher().executorService();
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("HTTP dispatcher 未在 5s 内关闭，执行强制关闭");
                executor.shutdownNow();
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    logger.warn("HTTP dispatcher 强制关闭仍未完成，放弃等待");
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("等待 HTTP dispatcher 关闭时被中断");
            httpClient.dispatcher().executorService().shutdownNow();
        } catch (Exception e) {
            logger.warn("关闭 HTTP dispatcher 失败", e);
        }

        // 3. 清理连接池
        try {
            httpClient.connectionPool().evictAll();
        } catch (Exception e) {
            logger.warn("清理 HTTP 连接池失败", e);
        }

        // 4. 关闭可选的缓存
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
    @Override
    public CompletableFuture<ApiMessageCompleteEvent> streamMessage(
            List<ConversationMessage> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            Consumer<StreamEvent> eventConsumer) {

        return streamMessageWithRetry(messages, systemPrompt, tools, eventConsumer, 0);
    }

    /**
     * 取消当前所有活跃的流式请求（F-P0-3）。
     *
     * 保持 close() 不会被触发的情况下，仅主动关闭当前 SSE 连接，
     * 使得用户中断后仍可继续使用该 Provider 发起新请求。
     *
     * 实现细节：EventSource.cancel() 并不保证后续触发 onClosed/onFailure 回调，
     * 因此这里必须**主动**将所有活跃 future 以 CancellationException 完成，
     * 否则上层 .join() 会永久阻塞在已被取消的连接上。
     */
    @Override
    public void cancelAllActiveRequests() {
        // 1. 关闭 SSE 连接
        synchronized (activeEventSources) {
            for (EventSource es : activeEventSources) {
                try {
                    es.cancel();
                } catch (Exception e) {
                    logger.warn("取消 SSE 连接失败", e);
                }
            }
            activeEventSources.clear();
        }
        // 2. 主动让所有未完成的 future 以 CancellationException 结束
        synchronized (activeFutures) {
            for (CompletableFuture<ApiMessageCompleteEvent> f : activeFutures) {
                if (!f.isDone()) {
                    f.cancel(true);
                }
            }
            activeFutures.clear();
        }
        logger.info("已取消所有活跃的 LLM 请求");
    }

    @Override
    public String getProviderName() {
        return "openai-compatible";
    }

    @Override
    public String getModelName() {
        return model;
    }

    private CompletableFuture<ApiMessageCompleteEvent> streamMessageWithRetry(
            List<ConversationMessage> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            Consumer<StreamEvent> eventConsumer,
            int attempt) {

        CompletableFuture<ApiMessageCompleteEvent> future = new CompletableFuture<>();
        // 仅在最外层（attempt==0）注册 future，重试场景通过 whenComplete 级联完成，避免重复注册
        if (attempt == 0) {
            activeFutures.add(future);
            future.whenComplete((ok, err) -> activeFutures.remove(future));
        }

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
                    // 连接关闭时从活跃列表中移除，防止内存泄漏
                    activeEventSources.remove(eventSource);
                    collector.finalizeCollection();
                    future.complete(collector.buildCompleteEvent());
                }

                @Override
                public void onFailure(@NotNull EventSource eventSource,
                                      @Nullable Throwable throwable,
                                      @Nullable Response response) {
                    // 连接失败时也要从活跃列表中移除，防止内存泄漏
                    activeEventSources.remove(eventSource);
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

            EventSource eventSource = EventSources.createFactory(httpClient).newEventSource(request, listener);
            activeEventSources.add(eventSource);

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
            } else if (code >= 400 && code < 500) {
                // 4xx 客户端错误不应重试，使用 AuthenticationFailureException 标记
                return new AuthenticationFailureException(message + " (客户端错误，不可重试)");
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
        // 用户消息中可能混合 TextBlock、ImageBlock 和 ToolResultBlock
        // ToolResultBlock 在 OpenAI 中需要作为独立的 role=tool 消息发送
        List<ContentBlock> contentParts = new ArrayList<>();
        List<ToolResultBlock> toolResults = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof ToolResultBlock toolResult) {
                toolResults.add(toolResult);
            } else if (block instanceof TextBlock || block instanceof ImageBlock) {
                contentParts.add(block);
            }
        }

        // 先发送 tool 结果消息（必须紧跟在 assistant 的 tool_calls 之后）
        for (ToolResultBlock toolResult : toolResults) {
            ObjectNode toolMsg = messagesArray.addObject();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolResult.getToolUseId());
            toolMsg.put("content", toolResult.getContent());
        }

        // 再发送用户消息（如果有内容部分）
        if (!contentParts.isEmpty()) {
            ObjectNode userMsg = messagesArray.addObject();
            userMsg.put("role", "user");

            // F-P1-3：如果消息仅含纯文本，使用字符串格式（兼容性最好）；
            // 如果包含图片，使用 OpenAI content 数组格式。
            boolean hasImage = contentParts.stream().anyMatch(b -> b instanceof ImageBlock);
            if (hasImage) {
                ArrayNode contentArray = userMsg.putArray("content");
                for (ContentBlock part : contentParts) {
                    if (part instanceof TextBlock textBlock) {
                        ObjectNode textNode = contentArray.addObject();
                        textNode.put("type", "text");
                        textNode.put("text", textBlock.getText());
                    } else if (part instanceof ImageBlock imageBlock) {
                        ObjectNode imgNode = contentArray.addObject();
                        imgNode.put("type", "image_url");
                        ObjectNode imgUrlNode = imgNode.putObject("image_url");
                        imgUrlNode.put("url", imageBlock.toImageUrlValue());
                        imgUrlNode.put("detail", imageBlock.getDetail());
                    }
                }
            } else {
                // 纯文本：合并为单个字符串
                StringBuilder text = new StringBuilder();
                for (ContentBlock part : contentParts) {
                    if (part instanceof TextBlock t) {
                        text.append(t.getText());
                    }
                }
                userMsg.put("content", text.toString());
            }
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
