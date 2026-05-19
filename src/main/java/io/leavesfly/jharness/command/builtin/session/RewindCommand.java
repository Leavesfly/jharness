package io.leavesfly.jharness.command.builtin.session;

import io.leavesfly.jharness.command.CommandResult;
import io.leavesfly.jharness.command.SlashCommand;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.kernel.engine.model.ConversationMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * /rewind - 按 user 轮次回退对话（默认 1 轮）。
 */
public final class RewindCommand {

    private RewindCommand() {}

    public static SlashCommand create() {
        return SessionCommandSupport.cmd("rewind", "回退轮次", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }

            int turns = 1;
            String trimmed = SessionCommandSupport.joinArgs(args);
            if (!trimmed.isEmpty()) {
                try {
                    turns = Integer.parseInt(trimmed);
                } catch (NumberFormatException e) {
                    return CompletableFuture.completedFuture(CommandResult.error("无效的轮次数: " + trimmed));
                }
            }

            List<ConversationMessage> messages = engine.getMessages();
            int originalCount = messages.size();
            List<ConversationMessage> rewound = SessionTranscript.rewindTurns(messages, turns);
            engine.loadMessages(rewound);

            int removed = originalCount - rewound.size();
            return CompletableFuture.completedFuture(
                    CommandResult.success(String.format("已回退 %d 轮（删除 %d 条消息）", turns, removed)));
        });
    }
}
