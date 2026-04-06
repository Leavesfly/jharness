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
 * Issue 命令处理器
 *
 * 显示或更新项目 issue 上下文
 */
public class IssueCommandHandler {

    public static SlashCommand createIssueCommand() {
        return new SimpleSlashCommand("issue", "显示或更新项目 issue 上下文", (args, ctx, ec) -> {
            try {
                Path issuePath = getIssuePath(ctx.getCwd());
                String action = args.isEmpty() ? "show" : args.get(0);

                CommandResult result = switch (action) {
                    case "show" -> handleShow(issuePath);
                    case "set" -> handleSet(issuePath, args);
                    case "clear" -> handleClear(issuePath);
                    default -> CommandResult.success("用法: /issue [show|set TITLE :: BODY|clear]");
                };
                return CompletableFuture.completedFuture(result);
            } catch (Exception e) {
                return CompletableFuture.completedFuture(CommandResult.error("Issue 命令失败: " + e.getMessage()));
            }
        });
    }

    private static CommandResult handleShow(Path issuePath) {
        if (!Files.exists(issuePath)) {
            return CommandResult.success("无 issue 上下文。文件路径: " + issuePath);
        }
        try {
            String content = Files.readString(issuePath);
            return CommandResult.success(content);
        } catch (Exception e) {
            return CommandResult.error("读取 issue 文件失败: " + e.getMessage());
        }
    }

    private static CommandResult handleSet(Path issuePath, List<String> args) {
        if (args.size() < 2) {
            return CommandResult.error("用法: /issue set TITLE :: BODY");
        }

        String rest = String.join(" ", args.subList(1, args.size()));
        String[] parts = rest.split("::", 2);

        if (parts.length < 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            return CommandResult.error("用法: /issue set TITLE :: BODY");
        }

        String title = parts[0].trim();
        String body = parts[1].trim();
        String content = "# " + title + "\n\n" + body + "\n";

        try {
            Files.createDirectories(issuePath.getParent());
            Files.writeString(issuePath, content);
            return CommandResult.success("已保存 issue 上下文到 " + issuePath);
        } catch (Exception e) {
            return CommandResult.error("保存 issue 文件失败: " + e.getMessage());
        }
    }

    private static CommandResult handleClear(Path issuePath) {
        try {
            if (Files.exists(issuePath)) {
                Files.delete(issuePath);
                return CommandResult.success("已清除 issue 上下文。");
            }
            return CommandResult.success("无 issue 上下文可清除。");
        } catch (Exception e) {
            return CommandResult.error("清除 issue 文件失败: " + e.getMessage());
        }
    }

    private static Path getIssuePath(Path cwd) {
        return cwd.resolve(".jharness").resolve("issue.md");
    }
}
