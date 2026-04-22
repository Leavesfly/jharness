package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.WebFetchToolInput;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;
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
 * - 连接和读取超时限制在合理范围内，避免慢速攻击。
 */
public class WebFetchTool extends BaseTool<WebFetchToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(WebFetchTool.class);

    /** 允许的 URL 协议白名单。 */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

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
     * 校验 URL 是否安全，防止 SSRF 攻击。
     *
     * @return 若合法返回 null；否则返回错误信息
     */
    private static String validateUrl(String url) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "URL 格式非法: " + e.getMessage();
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            return "安全限制: 仅允许 http/https 协议，实际: " + scheme;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "URL 必须包含有效的主机名";
        }

        // 解析主机，阻止访问本地/内网地址（含 IP 直连和域名解析结果）
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress()
                        || addr.isAnyLocalAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    return "安全限制: 禁止访问内网/本地/多播地址 " + addr.getHostAddress();
                }
            }
        } catch (UnknownHostException e) {
            return "无法解析主机: " + host;
        }

        return null;
    }

    @Override
    public boolean isReadOnly(WebFetchToolInput input) {
        return true;
    }
}
