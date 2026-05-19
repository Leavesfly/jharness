package io.leavesfly.jharness.capability.hook.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.capability.hook.HookResult;
import io.leavesfly.jharness.capability.hook.schemas.HookDefinition;
import io.leavesfly.jharness.util.JacksonUtils;
import io.leavesfly.jharness.util.UrlSafetyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP Hook 执行器：向指定 URL POST 事件 payload。
 *
 * 安全策略（沿用原 HookExecutor 实现）：
 *   - URL 走 UrlSafetyValidator（SSRF 防护：拒绝回环/内网/云元数据等）；
 *   - HttpClient 配置 Redirect.NEVER，避免 302 → 内网绕过；
 *   - 请求体大小上限 10 MB，响应体大小上限 4 MB，超出截断并标记。
 */
public class HttpHookRunner implements HookRunner<HookDefinition.HttpHookDefinition> {

    private static final Logger logger = LoggerFactory.getLogger(HttpHookRunner.class);
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;
    private static final long MAX_HTTP_REQUEST_BODY_BYTES = 10L * 1024 * 1024;
    private static final long MAX_HTTP_RESPONSE_BODY_BYTES = 4L * 1024 * 1024;

    @Override
    public HookResult run(HookDefinition.HttpHookDefinition hook, HookRunContext ctx) {
        String safetyError = UrlSafetyValidator.validate(hook.getUrl());
        if (safetyError != null) {
            logger.warn("拒绝不安全的 Hook HTTP URL: {}, reason={}", hook.getUrl(), safetyError);
            return new HookResult(hook.getType(), false, null, true,
                    "HTTP Hook URL 不安全: " + safetyError);
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(hook.getTimeoutSeconds()))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            Map<String, Object> body = Map.of(
                    "event", ctx.getEvent().name(),
                    "payload", ctx.getPayload(),
                    "hook_depth", ctx.getCurrentDepth());
            byte[] bodyBytes = MAPPER.writeValueAsBytes(body);
            if (bodyBytes.length > MAX_HTTP_REQUEST_BODY_BYTES) {
                return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(),
                        "HTTP Hook 请求体超过上限 " + MAX_HTTP_REQUEST_BODY_BYTES
                                + " 字节，实际 " + bodyBytes.length);
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(hook.getUrl()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .timeout(Duration.ofSeconds(hook.getTimeoutSeconds()))
                    .header("Content-Type", "application/json");

            for (Map.Entry<String, String> header : hook.getHeaders().entrySet()) {
                requestBuilder.header(header.getKey(), header.getValue());
            }

            HttpResponse<byte[]> response = client.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

            byte[] respBytes = response.body() == null ? new byte[0] : response.body();
            boolean truncated = false;
            if (respBytes.length > MAX_HTTP_RESPONSE_BODY_BYTES) {
                byte[] cut = new byte[(int) MAX_HTTP_RESPONSE_BODY_BYTES];
                System.arraycopy(respBytes, 0, cut, 0, cut.length);
                respBytes = cut;
                truncated = true;
            }
            String output = new String(respBytes, StandardCharsets.UTF_8);
            if (truncated) {
                output = output + "\n...[响应被截断，超过 " + MAX_HTTP_RESPONSE_BODY_BYTES + " 字节]";
            }

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;

            return new HookResult(
                    hook.getType(), success, output,
                    hook.isBlockOnFailure() && !success,
                    success ? null : String.format("HTTP hook returned %d: %s", response.statusCode(), output));
        } catch (Exception e) {
            return new HookResult(hook.getType(), false, null, hook.isBlockOnFailure(), e.getMessage());
        }
    }
}
