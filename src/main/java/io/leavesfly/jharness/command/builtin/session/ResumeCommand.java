package io.leavesfly.jharness.command.builtin.session;

import io.leavesfly.jharness.command.CommandContext;
import io.leavesfly.jharness.command.CommandResult;
import io.leavesfly.jharness.command.SlashCommand;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.capability.session.SessionSnapshot;
import io.leavesfly.jharness.capability.session.SessionStorage;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * /resume - 恢复会话，不带参数时列出最近 10 个会话。
 */
public final class ResumeCommand {

    private ResumeCommand() {}

    public static SlashCommand create(SessionStorage sessionStorage) {
        return SessionCommandSupport.cmd("resume", "恢复会话", (args, ctx, ec) -> {
            String trimmed = SessionCommandSupport.joinArgs(args);
            if (trimmed.isEmpty()) {
                return listRecentSessions(sessionStorage, ctx);
            }

            SessionSnapshot snapshot = sessionStorage.loadSession(trimmed);
            if (snapshot == null) {
                return CompletableFuture.completedFuture(
                        CommandResult.error("未找到会话: " + trimmed));
            }

            QueryEngine engine = ctx.getEngine();
            if (engine != null) {
                engine.loadMessages(snapshot.getMessages());
            }

            String msg = String.format(
                    "已恢复会话 %s\n消息数: %d\n模型: %s\n摘要: %s",
                    trimmed, snapshot.getMessageCount(),
                    snapshot.getModel(), snapshot.getSummary());
            return CompletableFuture.completedFuture(CommandResult.success(msg));
        });
    }

    private static CompletableFuture<CommandResult> listRecentSessions(SessionStorage sessionStorage, CommandContext ctx) {
        List<SessionSnapshot> sessions = sessionStorage.listSessions(10);
        if (sessions.isEmpty()) {
            return CompletableFuture.completedFuture(CommandResult.success("没有找到已保存的会话"));
        }
        StringBuilder sb = new StringBuilder("最近的会话:\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (SessionSnapshot s : sessions) {
            String time = s.getCreatedAt() != null ? fmt.format(s.getCreatedAt()) : "未知";
            sb.append(String.format("  %s - %s (%d 条消息)\n",
                    s.getSessionId(), time, s.getMessageCount()));
            if (s.getSummary() != null) {
                sb.append("    摘要: ").append(s.getSummary()).append("\n");
            }
        }
        sb.append("\n使用 /resume <会话ID> 恢复");
        return CompletableFuture.completedFuture(CommandResult.success(sb.toString().trim()));
    }
}
