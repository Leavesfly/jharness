package io.leavesfly.jharness.core.engine;

/**
 * 当会话/日预算超限时抛出（F-P0-5）。
 *
 * 调用方应捕获该异常并友好提示用户，而非作为普通 RuntimeException 中断程序。
 */
public class BudgetExceededException extends RuntimeException {
    public BudgetExceededException(String message) {
        super(message);
    }
}
