package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.WebFetchToolInput;
import io.leavesfly.jharness.util.UrlSafetyValidator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Web 内容获取工具
 *
 * 从 URL 获取网页内容，返回文本内容。
 *
 * 安全限制：
 * - 仅允许 http/https 协议，禁止 file://、ftp://、jar:// 等；
 * - 禁止访问本地回环、链路本地、内网私有地址，防止 SSRF 攻击；
 * - 禁止重定向跟随（避免 302 → 内网地址绕过）；
 * - 连接和读取超时限制在合理范围内，避免慢速攻击。
 */
public class WebFetchTool extends BaseTool<WebFetchToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(WebFetchTool.class);

    private final OkHttpClient httpClient;

    public WebFetchTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                // SSRF 防护：禁止跟随重定向，否则 302 到 http://127.0.0.1/admin 会绕过校验
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "从 URL 获取网页内容。返回页面的文本内容。";
    }

    @Override
    public Class<WebFetchToolInput> getInputClass() {
        return WebFetchToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(WebFetchToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            // 先做 SSRF 安全校验，再发起网络请求
            String validationError = validateUrl(input.getUrl());
            if (validationError != null) {
                logger.warn("拒绝不安全的 URL 请求: {} ({})", input.getUrl(), validationError);
                return ToolResult.error(validationError);
            }

            try {
                Request request = new Request.Builder()
                        .url(input.getUrl())
                        .header("User-Agent", "JHarness/1.0")
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        return ToolResult.error("HTTP 错误: " + response.code());
                    }

                    String body = response.body() != null ? response.body().string() : "";
                    
                    int maxLength = input.getMax_length() != null ? input.getMax_length() : 5000;
                    if (body.length() > maxLength) {
                        body = body.substring(0, maxLength) + "\n...(内容已截断)";
                    }

                    return ToolResult.success(body);
                }

            } catch (Exception e) {
                logger.error("Web 获取失败", e);
                return ToolResult.error("Web 获取失败: " + e.getMessage());
            }
        });
    }

    /**
     * 校验 URL 是否安全，防止 SSRF 攻击。委托给统一的 {@link UrlSafetyValidator}，
     * 与 McpClientManager / HookExecutor 共享同一套协议白名单 + 内网地址黑名单逻辑。
     *
     * @return 若合法返回 null；否则返回错误信息
     */
    private static String validateUrl(String url) {
        return UrlSafetyValidator.validate(url);
    }

    @Override
    public boolean isReadOnly(WebFetchToolInput input) {
        return true;
    }
}
