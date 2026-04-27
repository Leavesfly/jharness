package io.leavesfly.jharness.core.engine.stream;

/**
 * 使用量 / 成本增量报告事件。
 *
 * <p>由 {@code QueryEngine} 在每一轮 LLM 响应完成后推送，用于让 TUI / CLI / 集成方
 * 实时展示 token 消耗与累计花费，无需主动轮询 {@code CostTracker}。</p>
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>{@code inputTokens} / {@code outputTokens}：本轮 LLM 调用的 token 用量；</li>
 *   <li>{@code totalTokens}：会话累计 token（含缓存读写）；</li>
 *   <li>{@code sessionCostUsd} / {@code dailyCostUsd}：当前会话 / 当日累计 USD 成本；</li>
 *   <li>{@code dailyBudgetUsd}：当日预算上限，<= 0 表示未设置。</li>
 * </ul>
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>事件是只读快照，字段均为 final，上层可安全缓存；</li>
 *   <li>为向后兼容（老的 UI 实现不处理本事件），消费方遇到未知事件类型应忽略。</li>
 * </ul>
 */
public class UsageReport extends StreamEvent {

    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
    private final double sessionCostUsd;
    private final double dailyCostUsd;
    private final double dailyBudgetUsd;

    public UsageReport(long inputTokens, long outputTokens, long totalTokens,
                       double sessionCostUsd, double dailyCostUsd, double dailyBudgetUsd) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.sessionCostUsd = sessionCostUsd;
        this.dailyCostUsd = dailyCostUsd;
        this.dailyBudgetUsd = dailyBudgetUsd;
    }

    @Override
    public String getEventType() {
        return "usage_report";
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public double getSessionCostUsd() {
        return sessionCostUsd;
    }

    public double getDailyCostUsd() {
        return dailyCostUsd;
    }

    public double getDailyBudgetUsd() {
        return dailyBudgetUsd;
    }

    @Override
    public String toString() {
        return String.format(
                "UsageReport{in=%d, out=%d, total=%d, session=$%.4f, daily=$%.4f/$%.4f}",
                inputTokens, outputTokens, totalTokens,
                sessionCostUsd, dailyCostUsd, dailyBudgetUsd);
    }
}
