package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.WebFetchToolInput;
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
 */
public class WebFetchTool extends BaseTool<WebFetchToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(WebFetchTool.class);
    private final OkHttpClient httpClient;

    public WebFetchTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
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

    @Override
    public boolean isReadOnly(WebFetchToolInput input) {
        return true;
    }
}
