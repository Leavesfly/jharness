package io.leavesfly.jharness.integration.api.openai;

import java.net.URI;

/**
 * OpenAI 兼容端点 URL 工具：
 *   - 把 baseUrl 拼成完整 chat/completions 地址；
 *   - 判断是否为本地端点（用于空 API Key 放行 Ollama 等本地服务）。
 */
public final class OpenAiUrlResolver {

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";

    private OpenAiUrlResolver() {}

    /**
     * 根据 baseUrl 构建完整的 chat/completions 请求地址。
     *
     * 兼容以下几种 baseUrl 格式：
     * - https://api.openai.com           → https://api.openai.com/v1/chat/completions
     * - https://api.openai.com/v1        → https://api.openai.com/v1/chat/completions
     * - https://api.openai.com/v1/       → https://api.openai.com/v1/chat/completions
     * - https://xxx.com/compatible-mode/v1 → https://xxx.com/compatible-mode/v1/chat/completions
     */
    public static String buildCompletionsUrl(String baseUrl) {
        String url = baseUrl.replaceAll("/+$", "");
        if (url.endsWith("/v1")) {
            return url + CHAT_COMPLETIONS_SUFFIX;
        }
        return url + CHAT_COMPLETIONS_PATH;
    }

    /**
     * 判断 baseUrl 是否指向本地端点（localhost / 127.0.0.1 / ::1 / 0.0.0.0）。
     *
     * 优先使用 URI 解析 host，避免 "url 里恰好包含 localhost 字样但 host 并非本地地址"
     * 导致的误判；解析失败时回退到字符串包含判断，保持宽松兼容。
     */
    public static boolean isLocalEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return false;
        try {
            String normalized = baseUrl.contains("://") ? baseUrl : ("http://" + baseUrl);
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            return host.equals("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("0.0.0.0")
                    || host.equals("::1")
                    || host.equals("[::1]");
        } catch (Exception e) {
            String lower = baseUrl.toLowerCase();
            return lower.contains("localhost")
                    || lower.contains("127.0.0.1")
                    || lower.contains("0.0.0.0")
                    || lower.contains("::1");
        }
    }
}
