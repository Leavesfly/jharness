package io.leavesfly.jharness.command.builtin.session;

import io.leavesfly.jharness.command.CommandResult;
import io.leavesfly.jharness.command.SlashCommand;
import io.leavesfly.jharness.kernel.engine.QueryEngine;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * /export - 导出会话记录为 Markdown 文件。
 */
public final class ExportCommand {

    private ExportCommand() {}

    public static SlashCommand create() {
        return SessionCommandSupport.cmd("export", "导出记录", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }
            Path exportPath = SessionTranscript.exportMarkdown(ctx.getCwd(), engine.getMessages());
            return CompletableFuture.completedFuture(
                    CommandResult.success("会话已导出为 Markdown:\n" + exportPath));
        });
    }
}
