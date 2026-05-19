package io.leavesfly.jharness.command.commands.builtin.system;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.kernel.engine.CostTracker;
import io.leavesfly.jharness.kernel.engine.QueryEngine;

import java.util.concurrent.CompletableFuture;

import static io.leavesfly.jharness.command.commands.builtin.system.SystemCommandSupport.cmd;

/**
 * /cost - 显示估算费用（按 Claude 3.5 Sonnet 单价）。
 */
public final class CostCommand {

    private CostCommand() {}

    public static SlashCommand create() {
        return cmd("cost", "Token 费用", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }
            CostTracker tracker = engine.getCostTracker();
            if (tracker == null) {
                return CompletableFuture.completedFuture(CommandResult.error("成本追踪器未初始化"));
            }

            double inputCost = tracker.getTotalInputTokens() / 1_000_000.0 * 3.0;
            double outputCost = tracker.getTotalOutputTokens() / 1_000_000.0 * 15.0;
            double cacheReadCost = tracker.getTotalCacheReadTokens() / 1_000_000.0 * 0.3;
            double cacheCreateCost = tracker.getTotalCacheCreationTokens() / 1_000_000.0 * 3.75;
            double totalCost = inputCost + outputCost + cacheReadCost + cacheCreateCost;

            String msg = String.format(
                    "估算费用 (Claude 3.5 Sonnet):\n" +
                    "  输入: $%.4f (%d token)\n" +
                    "  输出: $%.4f (%d token)\n" +
                    "  缓存读取: $%.4f (%d token)\n" +
                    "  缓存创建: $%.4f (%d token)\n" +
                    "  总计: $%.4f",
                    inputCost, tracker.getTotalInputTokens(),
                    outputCost, tracker.getTotalOutputTokens(),
                    cacheReadCost, tracker.getTotalCacheReadTokens(),
                    cacheCreateCost, tracker.getTotalCacheCreationTokens(),
                    totalCost);
            return CompletableFuture.completedFuture(CommandResult.success(msg));
        });
    }
}
