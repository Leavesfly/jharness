package io.leavesfly.jharness.command.builtin.session;

import io.leavesfly.jharness.command.CommandContext;
import io.leavesfly.jharness.command.CommandResult;
import io.leavesfly.jharness.command.SlashCommand;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.capability.session.SessionSnapshot;
import io.leavesfly.jharness.capability.session.SessionStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /tag - 用一个名称创建命名快照（同时供 /session tag 子命令复用）。
 */
public final class TagCommand {

    static final Logger logger = LoggerFactory.getLogger(TagCommand.class);

    private TagCommand() {}

    public static SlashCommand create(SessionStorage sessionStorage) {
        return SessionCommandSupport.cmd("tag", "创建快照", (args, ctx, ec) -> {
            String name = SessionCommandSupport.joinArgs(args);
            if (name.isEmpty()) {
                return CompletableFuture.completedFuture(CommandResult.error("用法: /tag <名称>"));
            }
            return tagSession(sessionStorage, name, ctx);
        });
    }

    /** 包内复用：/session tag NAME 也走这里。 */
    static CompletableFuture<CommandResult> tagSession(SessionStorage sessionStorage, String name, CommandContext ctx) {
        QueryEngine engine = ctx.getEngine();
        if (engine == null) {
            return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
        }

        String safeName = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        String sessionId = UUID.randomUUID().toString().substring(0, 12);

        SessionSnapshot snapshot = SessionCommandSupport.createSnapshot(sessionId, ctx, engine);
        sessionStorage.saveSession(snapshot);

        Path exportPath = SessionTranscript.exportMarkdown(ctx.getCwd(), engine.getMessages());
        Path taggedMd = exportPath.getParent().resolve(safeName + ".md");
        try {
            Files.copy(exportPath, taggedMd, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warn("复制会话 markdown 到标签文件失败: src={}, dst={}", exportPath, taggedMd, e);
        }

        String msg = String.format(
                "快照已标记: %s\nJSON: session-%s.json\nMarkdown: %s",
                safeName, sessionId, taggedMd);
        return CompletableFuture.completedFuture(CommandResult.success(msg));
    }
}
