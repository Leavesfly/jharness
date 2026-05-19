package io.leavesfly.jharness.command.commands.builtin.session;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.kernel.engine.model.ConversationMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.leavesfly.jharness.command.commands.builtin.session.SessionCommandSupport.cmd;
import static io.leavesfly.jharness.command.commands.builtin.session.SessionCommandSupport.joinArgs;

/**
 * /compact - 把较旧的消息压成摘要，保留最近 N 条（默认 6）。
 */
public final class CompactCommand {

    private CompactCommand() {}

    public static SlashCommand create() {
        return cmd("compact", "压缩历史", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }

            int preserveRecent = 6;
            String trimmed = joinArgs(args);
            if (!trimmed.isEmpty()) {
                try {
                    preserveRecent = Integer.parseInt(trimmed);
                } catch (NumberFormatException e) {
                    return CompletableFuture.completedFuture(CommandResult.error("无效的保留数: " + trimmed));
                }
            }

            List<ConversationMessage> messages = engine.getMessages();
            int originalCount = messages.size();

            if (originalCount <= preserveRecent) {
                return CompletableFuture.completedFuture(
                        CommandResult.success(String.format("消息数 (%d) 未超过保留数 (%d)，无需压缩",
                                originalCount, preserveRecent)));
            }

            List<ConversationMessage> compacted = SessionTranscript.compact(messages, preserveRecent);
            engine.loadMessages(compacted);

            return CompletableFuture.completedFuture(
                    CommandResult.success(String.format(
                            "已压缩历史: %d 条消息 -> %d 条消息（保留最近 %d 条）",
                            originalCount, compacted.size(), preserveRecent)));
        });
    }
}
