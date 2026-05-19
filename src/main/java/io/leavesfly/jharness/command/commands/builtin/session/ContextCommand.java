package io.leavesfly.jharness.command.commands.builtin.session;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.kernel.engine.QueryEngine;

import java.util.concurrent.CompletableFuture;

import static io.leavesfly.jharness.command.commands.builtin.session.SessionCommandSupport.cmd;

/**
 * /context - 显示当前的系统提示词。
 */
public final class ContextCommand {

    private ContextCommand() {}

    public static SlashCommand create() {
        return cmd("context", "系统提示词", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }
            String prompt = engine.getSystemPrompt();
            if (prompt == null || prompt.isBlank()) {
                return CompletableFuture.completedFuture(CommandResult.success("系统提示词未设置"));
            }
            return CompletableFuture.completedFuture(
                    CommandResult.success("=== 当前系统提示词 ===\n" + prompt));
        });
    }
}
