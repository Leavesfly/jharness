package io.leavesfly.jharness.command.commands.builtin.session;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.kernel.engine.model.ConversationMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.leavesfly.jharness.command.commands.builtin.session.SessionCommandSupport.cmd;
import static io.leavesfly.jharness.command.commands.builtin.session.SessionCommandSupport.extractText;

/**
 * /summary - 总结对话历史（取前 10 条预览）。
 */
public final class SummaryCommand {

    private SummaryCommand() {}

    public static SlashCommand create() {
        return cmd("summary", "总结历史", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }
            List<ConversationMessage> messages = engine.getMessages();
            if (messages.isEmpty()) {
                return CompletableFuture.completedFuture(CommandResult.success("当前会话为空"));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("对话摘要 (").append(messages.size()).append(" 条消息):\n\n");

            int showCount = Math.min(10, messages.size());
            for (int i = 0; i < showCount; i++) {
                ConversationMessage msg = messages.get(i);
                String text = extractText(msg);
                if (text != null && !text.isEmpty()) {
                    String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                    sb.append(msg.getRole().name().toLowerCase()).append(": ").append(preview).append("\n");
                }
            }
            if (messages.size() > showCount) {
                sb.append("\n... (还有 ").append(messages.size() - showCount).append(" 条消息)");
            }
            return CompletableFuture.completedFuture(CommandResult.success(sb.toString().trim()));
        });
    }
}
