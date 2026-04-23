package io.leavesfly.jharness.core.engine;

import io.leavesfly.jharness.core.engine.model.UsageSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 成本追踪器
 *
 * 跟踪 API 调用的 token 使用情况和估算成本。
 * 使用 AtomicLong/AtomicInteger 保证线程安全。
 *
 * F-P0-5 升级：
 * - 新增模型名 + 价格表，实时估算 USD 成本；
 * - 新增每日预算上限 {@code dailyBudgetUsd}，超限时抛出 {@link BudgetExceededException}；
 * - 支持按日期自动重置日度计数（跨日首次 {@link #addUsage} 会重置当日累计）。
 */
public class CostTracker {
    private final AtomicLong totalInputTokens = new AtomicLong();
    private final AtomicLong totalOutputTokens = new AtomicLong();
    private final AtomicLong totalCacheReadTokens = new AtomicLong();
    private final AtomicLong totalCacheCreationTokens = new AtomicLong();
    private final AtomicInteger requestCount = new AtomicInteger();

    /** 当前使用的模型名（用于价格查询），通过 {@link #setModelName(String)} 注入。 */
    private volatile String modelName = "";

    /** 每日预算上限（USD），&lt;= 0 表示不限制。 */
    private volatile BigDecimal dailyBudgetUsd = BigDecimal.ZERO;

    /** 当日累计成本（USD）。 */
    private final AtomicReference<BigDecimal> dailyCostUsd = new AtomicReference<>(BigDecimal.ZERO);
    /** 当日累计成本所属日期，跨日时自动重置。 */
    private final AtomicReference<LocalDate> dailyDate = new AtomicReference<>(LocalDate.now());
    /** 会话总成本（USD），不会自动重置。 */
    private final AtomicReference<BigDecimal> sessionCostUsd = new AtomicReference<>(BigDecimal.ZERO);

    /**
     * 添加使用量记录。
     *
     * 同时会：
     * 1. 累计 token 计数；
     * 2. 按当前模型估算成本并累计到会话/当日；
     * 3. 若跨日，自动重置当日累计；
     * 4. 若设置了日预算且超限，抛出 {@link BudgetExceededException}。
     *
     * @param usage 使用量快照
     * @throws BudgetExceededException 当日累计成本超过 {@link #dailyBudgetUsd}
     */
    public void addUsage(UsageSnapshot usage) {
        if (usage == null) return;

        long in = usage.getInputTokens();
        long out = usage.getOutputTokens();
        totalInputTokens.addAndGet(in);
        totalOutputTokens.addAndGet(out);
        totalCacheReadTokens.addAndGet(usage.getCacheReadInputTokens());
        totalCacheCreationTokens.addAndGet(usage.getCacheCreationInputTokens());
        requestCount.incrementAndGet();

        // 估算本次调用成本
        BigDecimal cost = ModelPricing.estimateCost(modelName, in, out);
        sessionCostUsd.updateAndGet(prev -> prev.add(cost));

        // 跨日检查 + 累计
        LocalDate today = LocalDate.now();
        LocalDate prevDate = dailyDate.getAndSet(today);
        if (!today.equals(prevDate)) {
            dailyCostUsd.set(BigDecimal.ZERO);
        }
        BigDecimal newDaily = dailyCostUsd.updateAndGet(prev -> prev.add(cost));

        // 预算检查（dailyBudget <= 0 表示不限制）
        if (dailyBudgetUsd.signum() > 0 && newDaily.compareTo(dailyBudgetUsd) > 0) {
            throw new BudgetExceededException(String.format(
                    "已达到每日预算上限 $%.4f（当前累计 $%.4f），请在 Settings 中调整 dailyBudgetUsd 或等待次日重置。",
                    dailyBudgetUsd.doubleValue(), newDaily.doubleValue()));
        }
    }

    /** 设置当前模型名（用于价格查询）。 */
    public void setModelName(String modelName) {
        this.modelName = modelName == null ? "" : modelName;
    }

    public String getModelName() {
        return modelName;
    }

    /** 设置每日预算上限（USD），&lt;= 0 表示不限制。 */
    public void setDailyBudgetUsd(BigDecimal dailyBudgetUsd) {
        this.dailyBudgetUsd = dailyBudgetUsd == null ? BigDecimal.ZERO : dailyBudgetUsd;
    }

    public BigDecimal getDailyBudgetUsd() {
        return dailyBudgetUsd;
    }

    /** 获取当日累计成本（USD，保留 6 位小数）。 */
    public BigDecimal getDailyCostUsd() {
        return dailyCostUsd.get().setScale(6, RoundingMode.HALF_UP);
    }

    /** 获取本会话累计成本（USD，保留 6 位小数）。 */
    public BigDecimal getSessionCostUsd() {
        return sessionCostUsd.get().setScale(6, RoundingMode.HALF_UP);
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
     * 重置计数器（包含成本，但保留模型名与预算上限配置）。
     */
    public void reset() {
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        totalCacheReadTokens.set(0);
        totalCacheCreationTokens.set(0);
        requestCount.set(0);
        sessionCostUsd.set(BigDecimal.ZERO);
        dailyCostUsd.set(BigDecimal.ZERO);
        dailyDate.set(LocalDate.now());
    }

    @Override
    public String toString() {
        return String.format(
                "CostTracker{model=%s, requests=%d, input=%d, output=%d, total=%d, session=$%.4f, daily=$%.4f/$%.4f}",
                modelName, getRequestCount(), getTotalInputTokens(), getTotalOutputTokens(),
                getTotalTokens(), getSessionCostUsd().doubleValue(),
                getDailyCostUsd().doubleValue(), dailyBudgetUsd.doubleValue());
    }

    /**
     * 转换为 UsageSnapshot
     *
     * 注意：UsageSnapshot 使用 int 存储，若 token 数超过 Integer.MAX_VALUE 会截断。
     * 实际场景中单次会话不会达到此量级，但仍做安全截断处理。
     */
    public UsageSnapshot toUsageSnapshot() {
        return new UsageSnapshot(
                clampToInt(getTotalInputTokens()),
                clampToInt(getTotalOutputTokens()),
                clampToInt(getTotalCacheReadTokens()),
                clampToInt(getTotalCacheCreationTokens()));
    }

    /**
     * 将 long 安全截断为 int，防止溢出导致负数
     */
    private static int clampToInt(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
