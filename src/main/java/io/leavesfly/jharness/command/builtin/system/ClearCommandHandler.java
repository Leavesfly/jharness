package io.leavesfly.jharness.command.builtin.system;

import io.leavesfly.jharness.command.CommandContext;
import io.leavesfly.jharness.command.CommandResult;
import io.leavesfly.jharness.command.SlashCommand;
import io.leavesfly.jharness.kernel.engine.stream.StreamEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 清除会话历史命令处理器
 */
public class ClearCommandHandler implements SlashCommand {
    @Override
    public String getName() {
        return "clear";
    }

    @Override
    public String getDescription() {
        return "清除当前会话历史";
    }

    @Override
    public CompletableFuture<CommandResult> execute(List<String> args, CommandContext context, Consumer<StreamEvent> eventConsumer) {
        return CompletableFuture.supplyAsync(() -> {
            context.getEngine().clear();
            return CommandResult.success("会话历史已清除");
        });
    }
}
