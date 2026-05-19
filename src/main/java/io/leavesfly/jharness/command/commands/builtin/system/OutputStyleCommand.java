package io.leavesfly.jharness.command.commands.builtin.system;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.kernel.state.AppStateStore;
import io.leavesfly.jharness.prompt.outputstyles.OutputStyle;
import io.leavesfly.jharness.prompt.outputstyles.OutputStyleLoader;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static io.leavesfly.jharness.command.commands.builtin.system.SystemCommandSupport.cmd;
import static io.leavesfly.jharness.command.commands.builtin.system.SystemCommandSupport.joinArgs;

/**
 * /output-style - 输出样式管理（show/list/set）。
 */
public final class OutputStyleCommand {

    private OutputStyleCommand() {}

    public static SlashCommand create() {
        return cmd("output-style", "输出样式", (args, ctx, ec) -> {
            Settings settings = ctx.getSettings();
            AppStateStore stateStore = ctx.getAppStateStore();

            List<OutputStyle> styles = OutputStyleLoader.loadOutputStyles();
            Map<String, OutputStyle> available = styles.stream()
                    .collect(Collectors.toMap(OutputStyle::getName, s -> s));

            String current = settings != null ? settings.getOutputStyle() : "default";
            if (stateStore != null) {
                current = stateStore.get().getOutputStyle();
            }

            String joined = joinArgs(args);
            String[] parts = joined.split("\\s+", 2);
            String subcmd = parts[0];

            if (joined.isEmpty() || "show".equals(subcmd)) {
                return CompletableFuture.completedFuture(
                        CommandResult.success("输出样式: " + current));
            }

            if ("list".equals(subcmd)) {
                String list = styles.stream()
                        .map(s -> s.getName() + " [" + s.getSource() + "]")
                        .collect(Collectors.joining("\n"));
                return CompletableFuture.completedFuture(CommandResult.success(list));
            }

            if ("set".equals(subcmd) && parts.length == 2) {
                String name = parts[1].trim();
                if (!available.containsKey(name)) {
                    return CompletableFuture.completedFuture(
                            CommandResult.error("未知输出样式: " + name
                                    + "\n可用样式: "
                                    + available.keySet().stream().sorted()
                                    .collect(Collectors.joining(", "))));
                }
                if (settings != null) {
                    settings.setOutputStyle(name);
                    settings.save();
                }
                if (stateStore != null) {
                    stateStore.set(s -> s.setOutputStyle(name));
                }
                return CompletableFuture.completedFuture(
                        CommandResult.success("输出样式已设置为: " + name));
            }

            return CompletableFuture.completedFuture(
                    CommandResult.error("用法: /output-style [show|list|set NAME]"));
        });
    }
}
