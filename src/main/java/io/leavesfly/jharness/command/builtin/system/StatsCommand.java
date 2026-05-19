package io.leavesfly.jharness.command.builtin.system;

import io.leavesfly.jharness.command.CommandResult;
import io.leavesfly.jharness.command.SlashCommand;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.kernel.engine.CostTracker;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.kernel.state.AppState;
import io.leavesfly.jharness.kernel.state.AppStateStore;

import java.util.concurrent.CompletableFuture;

import static io.leavesfly.jharness.command.builtin.system.SystemCommandSupport.cmd;

/**
 * /stats - 会话统计：消息数、工具调用、成本、模式等。
 */
public final class StatsCommand {

    private StatsCommand() {}

    public static SlashCommand create() {
        return cmd("stats", "会话统计", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }

            Settings settings = ctx.getSettings();
            AppStateStore stateStore = ctx.getAppStateStore();

            int msgCount = engine.getMessages().size();
            CostTracker tracker = engine.getCostTracker();

            int toolCalls = 0;
            for (var msg : engine.getMessages()) {
                toolCalls += msg.getToolUses().size();
            }

            String outputStyle = "default";
            boolean vimEnabled = false;
            boolean voiceEnabled = false;
            String effort = "medium";
            int passes = 1;
            if (stateStore != null) {
                AppState state = stateStore.get();
                outputStyle = state.getOutputStyle();
                vimEnabled = state.isVimEnabled();
                voiceEnabled = state.isVoiceEnabled();
                effort = state.getEffort();
                passes = state.getPasses();
            } else if (settings != null) {
                outputStyle = settings.getOutputStyle();
                vimEnabled = settings.isVimEnabled();
                voiceEnabled = settings.isVoiceEnabled();
                effort = settings.getEffort();
                passes = settings.getPasses();
            }

            StringBuilder sb = new StringBuilder("会话统计:\n");
            sb.append("  消息数: ").append(msgCount).append("\n");
            sb.append("  工具调用: ").append(toolCalls).append("\n");
            if (tracker != null) {
                sb.append("  请求次数: ").append(tracker.getRequestCount()).append("\n");
                sb.append("  估算 token: ").append(tracker.getTotalTokens()).append("\n");
            }
            sb.append("  output_style: ").append(outputStyle).append("\n");
            sb.append("  vim_mode: ").append(vimEnabled ? "on" : "off").append("\n");
            sb.append("  voice_mode: ").append(voiceEnabled ? "on" : "off").append("\n");
            sb.append("  effort: ").append(effort).append("\n");
            sb.append("  passes: ").append(passes);

            return CompletableFuture.completedFuture(CommandResult.success(sb.toString()));
        });
    }
}
