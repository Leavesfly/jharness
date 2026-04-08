package io.leavesfly.jharness.integration.api.errors;

/**
 * OpenHarness API 异常基类
 */
public class OpenHarnessApiException extends RuntimeException {
    private final int statusCode;

    public OpenHarnessApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public OpenHarnessApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
