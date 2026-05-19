package io.leavesfly.jharness.command.commands.builtin.system;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.memory.MemoryManager;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.leavesfly.jharness.command.commands.builtin.system.SystemCommandSupport.cmd;
import static io.leavesfly.jharness.command.commands.builtin.system.SystemCommandSupport.getProjectName;
import static io.leavesfly.jharness.command.commands.builtin.system.SystemCommandSupport.joinArgs;

/**
 * /memory - 项目记忆管理（list/add/remove）。
 */
public final class MemoryCommand {

    private MemoryCommand() {}

    public static SlashCommand create(MemoryManager memoryManager) {
        return cmd("memory", "项目内存", (args, ctx, ec) -> {
            String joined = joinArgs(args);
            String[] parts = joined.isEmpty() ? new String[0] : joined.split("\\s+");
            String subcmd = parts.length > 0 ? parts[0] : "list";
            String project = getProjectName(ctx);

            return switch (subcmd) {
                case "list", "ls" -> listMemories(memoryManager, project);
                case "add" -> {
                    if (parts.length < 2) {
                        yield CompletableFuture.completedFuture(CommandResult.error("用法: /memory add <标题> <内容>"));
                    }
                    String title = parts[1];
                    String content = parts.length > 2 ? String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)) : "";
                    yield addMemory(memoryManager, project, title, content);
                }
                case "remove", "rm" -> {
                    if (parts.length < 2) {
                        yield CompletableFuture.completedFuture(CommandResult.error("用法: /memory remove <标题>"));
                    }
                    yield removeMemory(memoryManager, project, parts[1]);
                }
                default -> listMemories(memoryManager, project);
            };
        });
    }

    private static CompletableFuture<CommandResult> listMemories(MemoryManager memoryManager, String project) {
        List<String> memories = memoryManager.listMemories(project);
        if (memories.isEmpty()) {
            return CompletableFuture.completedFuture(CommandResult.success("没有已保存的记忆"));
        }
        StringBuilder sb = new StringBuilder("项目记忆:\n");
        for (String title : memories) {
            sb.append("  - ").append(title).append("\n");
        }
        return CompletableFuture.completedFuture(CommandResult.success(sb.toString().trim()));
    }

    private static CompletableFuture<CommandResult> addMemory(MemoryManager memoryManager, String project,
                                                              String title, String content) {
        memoryManager.addMemory(project, title, content);
        return CompletableFuture.completedFuture(CommandResult.success("记忆已添加: " + title));
    }

    private static CompletableFuture<CommandResult> removeMemory(MemoryManager memoryManager, String project, String title) {
        boolean removed = memoryManager.removeMemory(project, title);
        return CompletableFuture.completedFuture(removed
                ? CommandResult.success("记忆已删除: " + title)
                : CommandResult.error("未找到记忆: " + title));
    }
}
