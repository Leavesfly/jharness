package io.leavesfly.jharness.command.builtin.session;

import io.leavesfly.jharness.command.CommandResult;
import io.leavesfly.jharness.command.SlashCommand;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.capability.session.SessionSnapshot;
import io.leavesfly.jharness.capability.session.SessionStorage;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /share - 创建可分享的快照：导出 Markdown + 保存 JSON Snapshot。
 */
public final class ShareCommand {

    private ShareCommand() {}

    public static SlashCommand create(SessionStorage sessionStorage) {
        return SessionCommandSupport.cmd("share", "分享快照", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }
            Path exportPath = SessionTranscript.exportMarkdown(ctx.getCwd(), engine.getMessages());

            String sessionId = UUID.randomUUID().toString().substring(0, 12);
            SessionSnapshot snapshot = SessionCommandSupport.createSnapshot(sessionId, ctx, engine);
            sessionStorage.saveSession(snapshot);

            String msg = String.format(
                    "会话快照已创建:\nID: %s\nMarkdown: %s\nJSON: session-%s.json",
                    sessionId, exportPath, sessionId);
            return CompletableFuture.completedFuture(CommandResult.success(msg));
        });
    }
}
