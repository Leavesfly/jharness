package io.leavesfly.jharness.command.builtin.system;

import io.leavesfly.jharness.command.CommandContext;
import io.leavesfly.jharness.command.CommandResult;
import io.leavesfly.jharness.command.SlashCommand;
import io.leavesfly.jharness.kernel.engine.stream.StreamEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 退出命令处理器
 */
public class ExitCommandHandler implements SlashCommand {
    @Override
    public String getName() {
        return "exit";
    }

    @Override
    public String getDescription() {
        return "退出应用程序";
    }

    @Override
    public CompletableFuture<CommandResult> execute(List<String> args, CommandContext context, Consumer<StreamEvent> eventConsumer) {
        return CompletableFuture.supplyAsync(() -> {
            return CommandResult.exit("再见！");
        });
    }
}
