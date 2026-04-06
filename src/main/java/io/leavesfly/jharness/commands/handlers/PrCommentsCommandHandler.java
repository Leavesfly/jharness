package io.leavesfly.jharness.commands.handlers;

import io.leavesfly.jharness.commands.CommandContext;
import io.leavesfly.jharness.commands.CommandResult;
import io.leavesfly.jharness.commands.SimpleSlashCommand;
import io.leavesfly.jharness.commands.SlashCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * PR 评论命令处理器
 *
 * 显示或更新项目 PR 评论上下文
 */
public class PrCommentsCommandHandler {

    public static SlashCommand createPrCommentsCommand() {
        return new SimpleSlashCommand("pr_comments", "显示或更新项目 PR 评论上下文", (args, ctx, ec) -> {
            try {
                Path commentsPath = getPrCommentsPath(ctx.getCwd());
                String action = args.isEmpty() ? "show" : args.get(0);

                CommandResult result = switch (action) {
                    case "show" -> handleShow(commentsPath);
                    case "add" -> handleAdd(commentsPath, args);
                    case "clear" -> handleClear(commentsPath);
                    default -> CommandResult.success("用法: /pr_comments [show|add FILE[:LINE] :: COMMENT|clear]");
                };
                return CompletableFuture.completedFuture(result);
            } catch (Exception e) {
                return CompletableFuture.completedFuture(CommandResult.error("PR 评论命令失败: " + e.getMessage()));
            }
        });
    }

    private static CommandResult handleShow(Path commentsPath) {
        if (!Files.exists(commentsPath)) {
            return CommandResult.success("无 PR 评论上下文。文件路径: " + commentsPath);
        }
        try {
            String content = Files.readString(commentsPath);
            return CommandResult.success(content);
        } catch (Exception e) {
            return CommandResult.error("读取 PR 评论文件失败: " + e.getMessage());
        }
    }

    private static CommandResult handleAdd(Path commentsPath, List<String> args) {
        if (args.size() < 2) {
            return CommandResult.error("用法: /pr_comments add FILE[:LINE] :: COMMENT");
        }

        String rest = String.join(" ", args.subList(1, args.size()));
        String[] parts = rest.split("::", 2);

        if (parts.length < 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            return CommandResult.error("用法: /pr_comments add FILE[:LINE] :: COMMENT");
        }

        String location = parts[0].trim();
        String comment = parts[1].trim();

        try {
            String existing = Files.exists(commentsPath) ? Files.readString(commentsPath) : "# PR Comments\n";
            if (!existing.endsWith("\n")) {
                existing += "\n";
            }
            existing += "- " + location + ": " + comment + "\n";

            Files.createDirectories(commentsPath.getParent());
            Files.writeString(commentsPath, existing);
            return CommandResult.success("已添加 PR 评论到 " + commentsPath);
        } catch (Exception e) {
            return CommandResult.error("保存 PR 评论失败: " + e.getMessage());
        }
    }

    private static CommandResult handleClear(Path commentsPath) {
        try {
            if (Files.exists(commentsPath)) {
                Files.delete(commentsPath);
                return CommandResult.success("已清除 PR 评论上下文。");
            }
            return CommandResult.success("无 PR 评论上下文可清除。");
        } catch (Exception e) {
            return CommandResult.error("清除 PR 评论文件失败: " + e.getMessage());
        }
    }

    private static Path getPrCommentsPath(Path cwd) {
        return cwd.resolve(".jharness").resolve("pr_comments.md");
    }
}
