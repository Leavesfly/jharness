package io.leavesfly.jharness.commands.handlers;

import io.leavesfly.jharness.commands.CommandContext;
import io.leavesfly.jharness.commands.CommandResult;
import io.leavesfly.jharness.commands.SlashCommand;
import io.leavesfly.jharness.engine.stream.StreamEvent;
import io.leavesfly.jharness.permissions.PermissionMode;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 状态命令处理器
 */
public class StatusCommandHandler implements SlashCommand {
    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String getDescription() {
        return "显示当前会话状态";
    }

    @Override
    public CompletableFuture<CommandResult> execute(List<String> args, CommandContext context, Consumer<StreamEvent> eventConsumer) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder status = new StringBuilder();
            status.append("=== 当前状态 ===\n");
            status.append("模型: ").append(context.getSettings().getModel()).append("\n");
            status.append("权限模式: ").append(context.getPermissionChecker().getMode()).append("\n");
            status.append("消息数: ").append(context.getEngine().getMessages().size()).append("\n");
            status.append("成本: ").append(context.getEngine().getCostTracker()).append("\n");
            status.append("工具数: ").append(context.getToolRegistry().size()).append("\n");
            
            return CommandResult.success(status.toString());
        });
    }
}
