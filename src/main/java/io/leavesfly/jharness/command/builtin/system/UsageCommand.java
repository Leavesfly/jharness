package io.leavesfly.jharness.command.builtin.system;

import io.leavesfly.jharness.command.CommandResult;
import io.leavesfly.jharness.command.SlashCommand;
import io.leavesfly.jharness.kernel.engine.CostTracker;
import io.leavesfly.jharness.kernel.engine.QueryEngine;

import java.util.concurrent.CompletableFuture;

import static io.leavesfly.jharness.command.builtin.system.SystemCommandSupport.cmd;

/**
 * /usage - 显示 token 使用情况。
 */
public final class UsageCommand {

    private UsageCommand() {}

    public static SlashCommand create() {
        return cmd("usage", "使用情况", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }
            CostTracker tracker = engine.getCostTracker();
            if (tracker == null) {
                return CompletableFuture.completedFuture(CommandResult.error("成本追踪器未初始化"));
            }

            String msg = String.format(
                    "Token 使用情况:\n" +
                    "  请求次数: %d\n" +
                    "  输入 token: %d\n" +
                    "  输出 token: %d\n" +
                    "  缓存读取: %d\n" +
                    "  缓存创建: %d\n" +
                    "  总计: %d",
                    tracker.getRequestCount(),
                    tracker.getTotalInputTokens(),
                    tracker.getTotalOutputTokens(),
                    tracker.getTotalCacheReadTokens(),
                    tracker.getTotalCacheCreationTokens(),
                    tracker.getTotalTokens());
            return CompletableFuture.completedFuture(CommandResult.success(msg));
        });
    }
}
