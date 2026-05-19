package io.leavesfly.jharness.integration.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.leavesfly.jharness.integration.api.errors.OpenHarnessApiException;
import io.leavesfly.jharness.integration.api.openai.OpenAiErrorClassifier;
import io.leavesfly.jharness.integration.api.openai.OpenAiHttpClient;
import io.leavesfly.jharness.integration.api.openai.OpenAiRequestBuilder;
import io.leavesfly.jharness.integration.api.openai.OpenAiSseStreamReader;
import io.leavesfly.jharness.integration.api.openai.OpenAiUrlResolver;
import io.leavesfly.jharness.integration.api.retry.RetryPolicy;
import io.leavesfly.jharness.kernel.engine.model.ConversationMessage;
import io.leavesfly.jharness.kernel.engine.stream.StreamEvent;
import okhttp3.MediaType;
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
 * 实现 {@link LlmProvider} 接口，统一 Provider 抽象。
 */
public class OpenAiApiClient implements LlmProvider {
    private static final Logger logger = LoggerFactory.getLogger(OpenAiApiClient.class);

    /** 默认超时常量：保留为常量以便单元测试覆盖，实际可通过构造器覆盖。 */
    static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 30;
    static final int DEFAULT_READ_TIMEOUT_SECONDS = 300;
    static final int DEFAULT_WRITE_TIMEOUT_SECONDS = 30;

    private final OpenAiHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenAiRequestBuilder requestBuilder;
    private final String completionsUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final RetryPolicy retryPolicy;

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
     * 构造 API 客户端（自定义超时）。
     *
     * 暴露超时参数，方便调用方从 Settings / 环境变量注入，避免硬编码 300s 在慢连接下不够或快连接下浪费。
     */
    public OpenAiApiClient(String baseUrl, String apiKey, String model, int maxTokens,
                           int connectTimeoutSeconds, int readTimeoutSeconds, int writeTimeoutSeconds) {
        String resolvedBaseUrl = baseUrl != null ? baseUrl : "https://api.openai.com";

        // 本地 Ollama 等端点不校验 Authorization，允许在空 Key 时用占位符启动。
        String effectiveApiKey = apiKey;
        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            if (OpenAiUrlResolver.isLocalEndpoint(resolvedBaseUrl)) {
                effectiveApiKey = "ollama";
                logger.info("检测到本地端点 {}，使用占位 API Key 启动（适配 Ollama 等本地 LLM 服务）", resolvedBaseUrl);
            } else {
                throw new IllegalArgumentException(
                        "API Key 不能为空，请设置环境变量 OPENAI_API_KEY 或 ANTHROPIC_API_KEY，或将 baseUrl 指向本地端点（如 http://localhost:11434/v1）");
            }
        }
        this.completionsUrl = OpenAiUrlResolver.buildCompletionsUrl(resolvedBaseUrl);
        logger.info("API 请求地址: {}, 超时(s) connect={}/read={}/write={}",
                completionsUrl, connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds);
        this.apiKey = effectiveApiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.retryPolicy = RetryPolicy.defaultPolicy();

        this.httpClient = new OpenAiHttpClient(connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds);
        this.objectMapper = new ObjectMapper();
        this.requestBuilder = new OpenAiRequestBuilder(objectMapper, model, maxTokens);
    }

    /** 关闭客户端，释放连接池和线程池资源（委托给 {@link OpenAiHttpClient#close()}）。 */
    @Override
    public void close() {
        httpClient.close();
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

    /** 取消所有活跃的流式请求（委托给 {@link OpenAiHttpClient#cancelAll()}）。 */
    @Override
    public void cancelAllActiveRequests() {
        httpClient.cancelAll();
    }

    @Override
    public String getProviderName() {
        return "openai-compatible";
    }

    @Override
    public String getModelName() {
        return model;
    }

    /**
     * 流式发送 + 重试。
     *
     * future 由 OpenAiHttpClient 跟踪用于 cancelAll；每次 SSE 连接也注册到 HttpClient，
     * 在 onClosed/onFailure 时解除注册。重试通过 whenComplete 级联，避免重复注册外层 future。
     */
    private CompletableFuture<ApiMessageCompleteEvent> streamMessageWithRetry(
            List<ConversationMessage> messages,
            String systemPrompt,
            List<Map<String, Object>> tools,
            Consumer<StreamEvent> eventConsumer,
            int attempt) {

        CompletableFuture<ApiMessageCompleteEvent> future = new CompletableFuture<>();
        if (attempt == 0) {
            httpClient.registerFuture(future);
        }

        try {
            ObjectNode requestBody = requestBuilder.buildRequestBody(messages, systemPrompt, tools);

            Request request = new Request.Builder()
                    .url(completionsUrl)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(
                            objectMapper.writeValueAsString(requestBody),
                            MediaType.parse("application/json")))
                    .build();

            OpenAiSseStreamReader reader = new OpenAiSseStreamReader(objectMapper, eventConsumer);

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
                        reader.handleEvent(data);
                    } catch (Exception e) {
                        logger.error("处理 SSE 事件时出错", e);
                    }
                }

                @Override
                public void onClosed(@NotNull EventSource eventSource) {
                    logger.debug("SSE 连接已关闭");
                    httpClient.unregisterEventSource(eventSource);
                    reader.finalizeCollection();
                    future.complete(reader.buildCompleteEvent());
                }

                @Override
                public void onFailure(@NotNull EventSource eventSource,
                                      @Nullable Throwable throwable,
                                      @Nullable Response response) {
                    httpClient.unregisterEventSource(eventSource);
                    OpenHarnessApiException apiException = OpenAiErrorClassifier.classify(throwable, response);
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

            EventSource eventSource = EventSources.createFactory(httpClient.okHttp())
                    .newEventSource(request, listener);
            httpClient.registerEventSource(eventSource);

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /** 兼容旧 API：保留 public static isLocalEndpoint 供外部调用。 */
    public static boolean isLocalEndpoint(String baseUrl) {
        return OpenAiUrlResolver.isLocalEndpoint(baseUrl);
    }
}
