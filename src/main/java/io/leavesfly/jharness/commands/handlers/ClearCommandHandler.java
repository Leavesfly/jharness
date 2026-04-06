package io.leavesfly.jharness.commands.handlers;

import io.leavesfly.jharness.commands.CommandContext;
import io.leavesfly.jharness.commands.CommandResult;
import io.leavesfly.jharness.commands.SlashCommand;
import io.leavesfly.jharness.engine.stream.StreamEvent;

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
