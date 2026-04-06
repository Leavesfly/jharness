package io.leavesfly.jharness.api.errors;

/**
 * 速率限制异常
 */
public class RateLimitFailureException extends OpenHarnessApiException {
    public RateLimitFailureException(String message) {
        super(message, 429);
    }
}
