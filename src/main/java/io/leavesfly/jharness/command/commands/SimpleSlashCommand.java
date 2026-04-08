package io.leavesfly.jharness.command.commands;

import io.leavesfly.jharness.core.engine.stream.StreamEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 斜杠命令实现类
 */
public class SimpleSlashCommand implements SlashCommand {
    private final String name;
    private final String description;
    private final CommandHandler handler;

    @FunctionalInterface
    public interface CommandHandler {
        CompletableFuture<CommandResult> handle(List<String> args, CommandContext context, Consumer<StreamEvent> eventConsumer);
    }

    public SimpleSlashCommand(String name, String description, CommandHandler handler) {
        this.name = name;
        this.description = description;
        this.handler = handler;
    }

    @Override
    public String getName() { return name; }

    @Override
    public String getDescription() { return description; }

    @Override
    public CompletableFuture<CommandResult> execute(List<String> args, CommandContext context, Consumer<StreamEvent> eventConsumer) {
        return handler.handle(args, context, eventConsumer);
    }
}
