package io.leavesfly.jharness.command.commands.builtin.session;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.capability.session.SessionStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static io.leavesfly.jharness.command.commands.builtin.session.SessionCommandSupport.cmd;
import static io.leavesfly.jharness.command.commands.builtin.session.SessionCommandSupport.getSessionDir;
import static io.leavesfly.jharness.command.commands.builtin.session.SessionCommandSupport.joinArgs;

/**
 * /session - 查看会话信息（show/ls/path/tag/clear）。
 */
public final class SessionCommand {

    private SessionCommand() {}

    public static SlashCommand create(SessionStorage sessionStorage) {
        return cmd("session", "查看会话", (args, ctx, ec) -> {
            String joined = joinArgs(args);
            String[] parts = joined.isEmpty() ? new String[0] : joined.split("\\s+");
            String subcmd = parts.length > 0 ? parts[0] : "show";

            return switch (subcmd) {
                case "ls" -> listSessionFiles(ctx);
                case "path" -> showSessionPath(ctx);
                case "tag" -> {
                    if (parts.length < 2) {
                        yield CompletableFuture.completedFuture(CommandResult.error("用法: /session tag <名称>"));
                    }
                    yield TagCommand.tagSession(sessionStorage, parts[1], ctx);
                }
                case "clear" -> clearSession(ctx);
                default -> showSessionStatus(ctx);
            };
        });
    }

    private static CompletableFuture<CommandResult> listSessionFiles(CommandContext ctx) {
        Path sessionDir = getSessionDir(ctx);
        try {
            if (!Files.exists(sessionDir)) {
                return CompletableFuture.completedFuture(CommandResult.success("会话目录不存在"));
            }
            StringBuilder sb = new StringBuilder("会话文件:\n");
            try (var stream = Files.list(sessionDir)) {
                stream.sorted().forEach(p -> sb.append("  ").append(p.getFileName()).append("\n"));
            }
            return CompletableFuture.completedFuture(CommandResult.success(sb.toString().trim()));
        } catch (IOException e) {
            return CompletableFuture.completedFuture(CommandResult.error("列出文件失败: " + e.getMessage()));
        }
    }

    private static CompletableFuture<CommandResult> showSessionPath(CommandContext ctx) {
        return CompletableFuture.completedFuture(
                CommandResult.success("会话目录: " + getSessionDir(ctx)));
    }

    private static CompletableFuture<CommandResult> showSessionStatus(CommandContext ctx) {
        QueryEngine engine = ctx.getEngine();
        Path sessionDir = getSessionDir(ctx);

        StringBuilder sb = new StringBuilder("会话状态:\n");
        sb.append("  目录: ").append(sessionDir).append("\n");
        sb.append("  消息数: ").append(engine != null ? engine.getMessages().size() : 0).append("\n");
        sb.append("  存在: ").append(Files.exists(sessionDir) ? "是" : "否");
        if (Files.exists(sessionDir.resolve("latest.json"))) {
            sb.append("\n  最新快照: 存在");
        }
        if (Files.exists(sessionDir.resolve("transcript.md"))) {
            sb.append("\n  导出文件: 存在");
        }
        return CompletableFuture.completedFuture(CommandResult.success(sb.toString()));
    }

    private static CompletableFuture<CommandResult> clearSession(CommandContext ctx) {
        Path sessionDir = getSessionDir(ctx);
        try {
            if (Files.exists(sessionDir)) {
                try (var stream = Files.list(sessionDir)) {
                    stream.filter(Files::isRegularFile).forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                    });
                }
            }
            Files.createDirectories(sessionDir);
            QueryEngine engine = ctx.getEngine();
            if (engine != null) {
                engine.clear();
            }
            return CompletableFuture.completedFuture(CommandResult.success("会话已清空"));
        } catch (IOException e) {
            return CompletableFuture.completedFuture(CommandResult.error("清除会话失败: " + e.getMessage()));
        }
    }
}
