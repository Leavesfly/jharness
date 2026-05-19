package io.leavesfly.jharness.command.commands.builtin.session;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.kernel.engine.CostTracker;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.kernel.engine.model.ContentBlock;
import io.leavesfly.jharness.kernel.engine.model.ConversationMessage;
import io.leavesfly.jharness.kernel.engine.model.MessageRole;
import io.leavesfly.jharness.kernel.engine.model.TextBlock;
import io.leavesfly.jharness.kernel.engine.model.UsageSnapshot;
import io.leavesfly.jharness.capability.session.SessionSnapshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * /session 域命令共享的小工具：构造 SimpleSlashCommand、消息内容提取、
 * 会话目录推断、Snapshot 构造。
 *
 * 抽自原 SessionCommandHandler，让 10 个子命令独立类不再重复同一组私有 helper。
 */
final class SessionCommandSupport {

    private SessionCommandSupport() {}

    static String joinArgs(List<String> args) {
        return args == null || args.isEmpty() ? "" : String.join(" ", args);
    }

    static SimpleSlashCommand cmd(String name, String desc, SimpleSlashCommand.CommandHandler handler) {
        return new SimpleSlashCommand(name, desc, handler);
    }

    /** 提取消息中第一个 TextBlock 的文本，找不到返回 null。 */
    static String extractText(ConversationMessage message) {
        for (ContentBlock content : message.getContent()) {
            if (content instanceof TextBlock textBlock) {
                return textBlock.getText();
            }
        }
        return null;
    }

    /** 从消息列表里找最后一条 assistant 的文本回复（去空白），找不到返回 null。 */
    static String findLastAssistantText(List<ConversationMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage msg = messages.get(i);
            if (MessageRole.ASSISTANT.equals(msg.getRole())) {
                String text = extractText(msg);
                if (text != null && !text.trim().isEmpty()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    /** 判断消息是否含非空文本内容。 */
    static boolean hasTextContent(ConversationMessage message) {
        String text = extractText(message);
        return text != null && !text.trim().isEmpty();
    }

    /** 推断当前会话目录（按 cwd 项目名 + cwd 字符串 hash）。 */
    static Path getSessionDir(CommandContext ctx) {
        return getSessionDirForCwd(ctx.getCwd());
    }

    static Path getSessionDirForCwd(Path cwd) {
        String projectName = cwd.getFileName() != null ? cwd.getFileName().toString() : "default";
        String digest = String.valueOf(Math.abs(cwd.toString().hashCode()));
        String sessionDirName = projectName + "-" + digest.substring(0, Math.min(8, digest.length()));
        return Path.of(System.getProperty("user.home"), ".openharness", "sessions", sessionDirName);
    }

    /** 用当前 engine 状态构建一个 SessionSnapshot，summary 取第一条 user 消息前 80 字符。 */
    static SessionSnapshot createSnapshot(String sessionId, CommandContext ctx, QueryEngine engine) {
        List<ConversationMessage> messages = engine.getMessages();
        String summary = messages.stream()
                .filter(m -> MessageRole.USER.equals(m.getRole()))
                .map(SessionCommandSupport::extractText)
                .filter(t -> t != null && !t.isEmpty())
                .findFirst()
                .map(t -> t.length() > 80 ? t.substring(0, 80) : t)
                .orElse("空会话");

        CostTracker tracker = engine.getCostTracker();
        UsageSnapshot usage = tracker != null ? tracker.toUsageSnapshot() : null;

        return new SessionSnapshot(
                sessionId,
                ctx.getCwd().toString(),
                ctx.getSettings() != null ? ctx.getSettings().getModel() : "unknown",
                messages,
                usage,
                Instant.now(),
                summary,
                messages.size());
    }
}
