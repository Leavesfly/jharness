package io.leavesfly.jharness.integration.api.retry;

import io.leavesfly.jharness.integration.api.errors.AuthenticationFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 重试策略
 *
 * 实现指数退避重试机制，用于处理 API 调用的临时失败。
 */
public class RetryPolicy {
    private static final Logger logger = LoggerFactory.getLogger(RetryPolicy.class);

    private final int maxRetries;
    private final long initialDelayMs;
    private final double backoffMultiplier;

    public RetryPolicy(int maxRetries, long initialDelayMs, double backoffMultiplier) {
        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
        this.backoffMultiplier = backoffMultiplier;
    }

    /**
     * 创建默认重试策略
     *
     * @return 默认重试策略（最多 3 次，初始延迟 1 秒，倍数 2）
     */
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, 1000, 2.0);
    }

    /**
     * 判断是否应该重试
     *
     * 认证失败（401）不应重试，限流（429）和服务端错误（5xx）可以重试。
     *
     * @param attempt   当前尝试次数（从 0 开始）
     * @param exception 发生的异常
     * @return 是否应该重试
     */
    public boolean shouldRetry(int attempt, Exception exception) {
        if (attempt >= maxRetries) {
            return false;
        }
        // 认证失败不应重试（使用 instanceof 代替字符串比较，更安全）
        if (exception instanceof AuthenticationFailureException) {
            logger.warn("认证失败，不进行重试");
            return false;
        }
        return true;
    }

    /**
     * 获取指定尝试次数对应的延迟时间
     *
     * @param attempt 当前尝试次数（从 0 开始）
     * @return 延迟毫秒数
     */
    public long getDelayMs(int attempt) {
        return (long) (initialDelayMs * Math.pow(backoffMultiplier, attempt));
    }

    /**
     * 执行带有重试逻辑的操作
     *
     * @param operation 要执行的操作
     * @param <T>       返回值类型
     * @return 操作结果
     * @throws Exception 如果所有重试都失败
     */
    public <T> T execute(Supplier<T> operation) throws Exception {
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    long delayMs = getDelayMs(attempt - 1);
                    logger.debug("重试第 {} 次，等待 {} ms", attempt, delayMs);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        // P2-M7：恢复中断位并立即终止重试
                        Thread.currentThread().interrupt();
                        throw new InterruptedException("重试等待期间被中断");
                    }
                }
                return operation.get();
            } catch (InterruptedException ie) {
                // 向上抛出中断异常，让调用方决定是否处理
                throw ie;
            } catch (Exception e) {
                lastException = e;
                logger.warn("尝试 {} 失败: {}", attempt + 1, e.getMessage());
                // P2-M7：若异常策略认为不应重试，立即抛出，不要再循环
                if (!shouldRetry(attempt, e)) {
                    break;
                }
            }
        }

        throw new RuntimeException("所有重试都失败了", lastException);
    }
}
