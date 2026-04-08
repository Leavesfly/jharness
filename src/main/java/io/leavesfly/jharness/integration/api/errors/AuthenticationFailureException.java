package io.leavesfly.jharness.integration.api.errors;

/**
 * 认证失败异常
 */
public class AuthenticationFailureException extends OpenHarnessApiException {
    public AuthenticationFailureException(String message) {
        super(message, 401);
    }
}
