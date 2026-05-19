package io.leavesfly.jharness.integration.api.openai;

import io.leavesfly.jharness.integration.api.errors.AuthenticationFailureException;
import io.leavesfly.jharness.integration.api.errors.OpenHarnessApiException;
import io.leavesfly.jharness.integration.api.errors.RateLimitFailureException;
import okhttp3.Response;
import org.jetbrains.annotations.Nullable;

/**
 * 把 SSE 失败时拿到的 throwable / response 翻译成有重试语义的 OpenHarness 异常。
 *
 *   - 401 → AuthenticationFailureException（不可重试）
 *   - 429 → RateLimitFailureException（可重试）
 *   - 其它 4xx → AuthenticationFailureException 标记客户端错误（不可重试）
 *   - 5xx / 网络异常 → OpenHarnessApiException（可重试）
 */
public final class OpenAiErrorClassifier {

    private OpenAiErrorClassifier() {}

    public static OpenHarnessApiException classify(@Nullable Throwable throwable, @Nullable Response response) {
        if (response != null) {
            int code = response.code();
            String message = "API 错误 (HTTP " + code + ")";
            if (code == 401) {
                return new AuthenticationFailureException(message);
            } else if (code == 429) {
                return new RateLimitFailureException(message);
            } else if (code >= 400 && code < 500) {
                return new AuthenticationFailureException(message + " (客户端错误，不可重试)");
            }
            return new OpenHarnessApiException(message, code);
        }
        if (throwable != null) {
            return new OpenHarnessApiException("SSE 连接失败: " + throwable.getMessage(), 500, throwable);
        }
        return new OpenHarnessApiException("未知错误", 500);
    }
}
