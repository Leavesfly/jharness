package io.leavesfly.jharness.command.commands.builtin.system;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.kernel.state.AppStateStore;

import java.util.concurrent.CompletableFuture;

import static io.leavesfly.jharness.command.commands.builtin.system.SystemCommandSupport.cmd;
import static io.leavesfly.jharness.command.commands.builtin.system.SystemCommandSupport.joinArgs;

/**
 * /vim - Vim 模式切换（show/on/off/toggle）。
 */
public final class VimCommand {

    private VimCommand() {}

    public static SlashCommand create() {
        return cmd("vim", "Vim 模式", (args, ctx, ec) -> {
            Settings settings = ctx.getSettings();
            if (settings == null) {
                return CompletableFuture.completedFuture(CommandResult.error("设置未初始化"));
            }

            String joined = joinArgs(args);
            AppStateStore stateStore = ctx.getAppStateStore();

            boolean current = settings.isVimEnabled();
            if (stateStore != null) {
                current = stateStore.get().isVimEnabled();
            }

            if (joined.isEmpty() || "show".equals(joined)) {
                return CompletableFuture.completedFuture(
                        CommandResult.success("Vim 模式: " + (current ? "on" : "off")));
            }

            boolean newValue;
            switch (joined) {
                case "on"     -> newValue = true;
                case "off"    -> newValue = false;
                case "toggle" -> newValue = !current;
                default -> {
                    return CompletableFuture.completedFuture(
                            CommandResult.error("用法: /vim [show|on|off|toggle]"));
                }
            }

            settings.setVimEnabled(newValue);
            settings.save();
            if (stateStore != null) {
                stateStore.set(s -> s.setVimEnabled(newValue));
            }
            return CompletableFuture.completedFuture(
                    CommandResult.success("Vim 模式已" + (newValue ? "开启" : "关闭")));
        });
    }
}
