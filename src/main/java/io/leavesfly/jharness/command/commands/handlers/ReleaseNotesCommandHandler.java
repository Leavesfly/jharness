package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.command.commands.SlashCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 发布说明命令处理器
 *
 * 显示最近的发布说明
 */
public class ReleaseNotesCommandHandler {

    public static SlashCommand createReleaseNotesCommand() {
        return new SimpleSlashCommand("release-notes", "显示最近的发布说明", (args, ctx, ec) -> {
            try {
                Path releaseNotesPath = ctx.getCwd().resolve("RELEASE_NOTES.md");
                if (Files.exists(releaseNotesPath)) {
                    String content = Files.readString(releaseNotesPath);
                    return CompletableFuture.completedFuture(CommandResult.success(content));
                }

                String message = String.join("\n", List.of(
                    "# Release Notes",
                    "",
                    "## 最新版本",
                    "- 完善了 MCP 资源工具支持",
                    "- 添加了 Cron 定时任务系统",
                    "- 实现了 Skills 加载器",
                    "- 完善了 Hooks 系统（4 种类型）",
                    "- 添加了 Plugins 系统",
                    "- 实现了 LSP 代码智能工具",
                    "- 添加了 Notebook 编辑工具",
                    "- 增强了命令系统（/issue, /pr_comments, /upgrade 等）"
                ));
                return CompletableFuture.completedFuture(CommandResult.success(message));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(CommandResult.error("发布说明命令失败: " + e.getMessage()));
            }
        });
    }
}
