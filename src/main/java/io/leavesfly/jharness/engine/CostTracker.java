package io.leavesfly.jharness.engine;

import io.leavesfly.jharness.engine.model.UsageSnapshot;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 成本追踪器
 *
 * 跟踪 API 调用的 token 使用情况和估算成本。
 * 使用 AtomicInteger 保证线程安全。
 */
public class CostTracker {
    private final AtomicLong totalInputTokens = new AtomicLong();
    private final AtomicLong totalOutputTokens = new AtomicLong();
    private final AtomicLong totalCacheReadTokens = new AtomicLong();
    private final AtomicLong totalCacheCreationTokens = new AtomicLong();
    private final AtomicInteger requestCount = new AtomicInteger();

    /**
     * 添加使用量记录
     *
     * @param usage 使用量快照
     */
    public void addUsage(UsageSnapshot usage) {
        if (usage == null) return;

        totalInputTokens.addAndGet(usage.getInputTokens());
        totalOutputTokens.addAndGet(usage.getOutputTokens());
        totalCacheReadTokens.addAndGet(usage.getCacheReadInputTokens());
        totalCacheCreationTokens.addAndGet(usage.getCacheCreationInputTokens());
        requestCount.incrementAndGet();
    }

    public long getTotalInputTokens() {
        return totalInputTokens.get();
    }

    public long getTotalOutputTokens() {
        return totalOutputTokens.get();
    }

    public long getTotalCacheReadTokens() {
        return totalCacheReadTokens.get();
    }

    public long getTotalCacheCreationTokens() {
        return totalCacheCreationTokens.get();
    }

    public int getRequestCount() {
        return requestCount.get();
    }

    /**
     * 获取总 token 数
     */
    public long getTotalTokens() {
        return totalInputTokens.get() + totalOutputTokens.get()
                + totalCacheReadTokens.get() + totalCacheCreationTokens.get();
    }

    /**
     * 重置计数器
     */
    public void reset() {
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        totalCacheReadTokens.set(0);
        totalCacheCreationTokens.set(0);
        requestCount.set(0);
    }

    @Override
    public String toString() {
        return String.format("CostTracker{requests=%d, input=%d, output=%d, cacheRead=%d, cacheCreate=%d, total=%d}",
                getRequestCount(), getTotalInputTokens(), getTotalOutputTokens(),
                getTotalCacheReadTokens(), getTotalCacheCreationTokens(), getTotalTokens());
    }

    /**
     * 转换为 UsageSnapshot
     */
    public UsageSnapshot toUsageSnapshot() {
        return new UsageSnapshot((int) getTotalInputTokens(), (int) getTotalOutputTokens(),
                (int) getTotalCacheReadTokens(), (int) getTotalCacheCreationTokens());
    }
}
