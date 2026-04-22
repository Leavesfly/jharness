package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.SleepToolInput;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 睡眠工具
 *
 * 延迟执行指定的秒数。用于等待外部进程或冷却。
 *
 * 改进点（P2-M22）：
 * - 使用 CompletableFuture.delayedExecutor 替代 Thread.sleep，避免占用 ForkJoinPool 工作线程；
 * - 当 seconds <= 0 时直接完成，不调度；
 * - 对负数/超限做显式参数校验，而非静默取 min。
 */
public class SleepTool extends BaseTool<SleepToolInput> {
    private static final int MAX_SLEEP_SECONDS = 60;

    @Override
    public String getName() {
        return "sleep";
    }

    @Override
    public String getDescription() {
        return "延迟执行指定的秒数。用于等待外部进程完成或避免速率限制。";
    }

    @Override
    public Class<SleepToolInput> getInputClass() {
        return SleepToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(SleepToolInput input, ToolExecutionContext context) {
        int requested = input.getSeconds();
        if (requested < 0) {
            return CompletableFuture.completedFuture(ToolResult.error("seconds 不能为负数: " + requested));
        }
        if (requested == 0) {
            return CompletableFuture.completedFuture(ToolResult.success("已等待 0 秒"));
        }
        int seconds = Math.min(requested, MAX_SLEEP_SECONDS);
        final int finalSeconds = seconds;
        return CompletableFuture.supplyAsync(
                () -> ToolResult.success("已等待 " + finalSeconds + " 秒"
                        + (requested > MAX_SLEEP_SECONDS ? "（已从 " + requested + " 截断）" : "")),
                CompletableFuture.delayedExecutor(seconds, TimeUnit.SECONDS));
    }

    @Override
    public boolean isReadOnly(SleepToolInput input) {
        return true;
    }
}
