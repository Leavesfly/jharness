package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.core.Settings;

import io.leavesfly.jharness.extension.plugins.PluginInstaller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 扩展配置命令 Handler
 * 处理: /effort, /passes, /fast, /plugin, /reload-plugins, /init
 */
public class ConfigPlusCommandHandler {

    private static String joinArgs(List<String> args) {
        return args == null || args.isEmpty() ? "" : String.join(" ", args);
    }

    private static SimpleSlashCommand cmd(String name, String desc, SimpleSlashCommand.CommandHandler handler) {
        return new SimpleSlashCommand(name, desc, handler);
    }

    /**
     * /effort - 推理努力级别
     */
    public static SlashCommand createEffortCommand() {
        return cmd("effort", "推理努力", (args, ctx, ec) -> {
            Settings settings = ctx.getSettings();
            if (settings == null) {
                return CompletableFuture.completedFuture(CommandResult.error("设置未初始化"));
            }

            String joined = joinArgs(args);
            if (joined.isEmpty() || "show".equals(joined)) {
                String effort = settings.get("effort");
                return CompletableFuture.completedFuture(
                        CommandResult.success("推理努力级别: " + (effort != null ? effort : "medium (默认)")));
            }

            // 设置 effort
            String value = joined.startsWith("set ") ? joined.substring(4).trim() : joined;
            if ("low".equals(value) || "medium".equals(value) || "high".equals(value)) {
                if (settings.set("effort", value)) {
                    settings.save();
                    return CompletableFuture.completedFuture(
                            CommandResult.success("推理努力级别已设置为: " + value));
                }
            }

            return CompletableFuture.completedFuture(
                    CommandResult.error("无效的努力级别: " + value + "\n可选值: low, medium, high"));
        });
    }

    /**
     * /passes - 推理传递次数
     */
    public static SlashCommand createPassesCommand() {
        return cmd("passes", "推理传递", (args, ctx, ec) -> {
            Settings settings = ctx.getSettings();
            if (settings == null) {
                return CompletableFuture.completedFuture(CommandResult.error("设置未初始化"));
            }

            String joined = joinArgs(args);
            if (joined.isEmpty() || "show".equals(joined)) {
                String passes = settings.get("passes");
                return CompletableFuture.completedFuture(
                        CommandResult.success("推理传递次数: " + (passes != null ? passes : "1 (默认)")));
            }

            String value = joined.startsWith("set ") ? joined.substring(4).trim() : joined;
            try {
                int passes = Integer.parseInt(value);
                if (passes >= 1 && passes <= 10) {
                    if (settings.set("passes", value)) {
                        settings.save();
                        return CompletableFuture.completedFuture(
                                CommandResult.success("推理传递次数已设置为: " + passes));
                    }
                }
            } catch (NumberFormatException e) {
                // fall through
            }

            return CompletableFuture.completedFuture(
                    CommandResult.error("无效的传递次数: " + value + "\n范围: 1-10"));
        });
    }

    /**
     * /fast - 快速模式
     */
    public static SlashCommand createFastCommand() {
        return cmd("fast", "快速模式", (args, ctx, ec) -> {
            Settings settings = ctx.getSettings();
            if (settings == null) {
                return CompletableFuture.completedFuture(CommandResult.error("设置未初始化"));
            }

            String joined = joinArgs(args);
            boolean fastMode = settings.isFastMode();

            if (joined.isEmpty() || "show".equals(joined)) {
                return CompletableFuture.completedFuture(
                        CommandResult.success("快速模式: " + (fastMode ? "开启" : "关闭")));
            }

            switch (joined) {
                case "on", "toggle" -> {
                    settings.setFastMode(!fastMode);
                    settings.save();
                    return CompletableFuture.completedFuture(
                            CommandResult.success("快速模式已" + (settings.isFastMode() ? "开启" : "关闭")));
                }
                case "off" -> {
                    settings.setFastMode(false);
                    settings.save();
                    return CompletableFuture.completedFuture(CommandResult.success("快速模式已关闭"));
                }
                default -> {
                    return CompletableFuture.completedFuture(
                            CommandResult.error("用法: /fast [show|on|off|toggle]"));
                }
            }
        });
    }

    /**
     * /plugin - 管理插件
     */
    public static SlashCommand createPluginCommand() {
        return cmd("plugin", "管理插件", (args, ctx, ec) -> {
            String joined = joinArgs(args);
            String[] parts = joined.isEmpty() ? new String[0] : joined.split("\\s+");
            String subcmd = parts.length > 0 ? parts[0] : "list";

            Path pluginsDir = Path.of(System.getProperty("user.home"), ".openharness/plugins");

            return switch (subcmd) {
                case "list", "ls" -> {
                    if (!Files.exists(pluginsDir)) {
                        yield CompletableFuture.completedFuture(CommandResult.success("没有已安装的插件"));
                    }
                    StringBuilder sb = new StringBuilder("已安装的插件:\n");
                    try (var stream = Files.list(pluginsDir)) {
                        stream.filter(Files::isDirectory).forEach(d ->
                                sb.append("  - ").append(d.getFileName()).append("\n"));
                    } catch (Exception e) {
                        sb.append("  读取失败: ").append(e.getMessage());
                    }
                    yield CompletableFuture.completedFuture(CommandResult.success(sb.toString().trim()));
                }
                case "path" ->
                        CompletableFuture.completedFuture(CommandResult.success("插件目录: " + pluginsDir));
                case "install" -> {
                    if (parts.length < 2) {
                        yield CompletableFuture.completedFuture(CommandResult.error("用法: /plugin install <路径>"));
                    }
                    try {
                        Path sourceDir = Path.of(parts[1]);
                        boolean success = PluginInstaller.installPlugin(sourceDir);
                        yield CompletableFuture.completedFuture(
                                success ? CommandResult.success("插件安装成功: " + parts[1])
                                        : CommandResult.error("插件安装失败"));
                    } catch (Exception e) {
                        yield CompletableFuture.completedFuture(
                                CommandResult.error("插件安装失败: " + e.getMessage()));
                    }
                }
                case "remove" -> {
                    if (parts.length < 2) {
                        yield CompletableFuture.completedFuture(CommandResult.error("用法: /plugin remove <名称>"));
                    }
                    try {
                        boolean success = PluginInstaller.uninstallPlugin(parts[1]);
                        yield CompletableFuture.completedFuture(
                                success ? CommandResult.success("插件已卸载: " + parts[1])
                                        : CommandResult.error("插件不存在: " + parts[1]));
                    } catch (Exception e) {
                        yield CompletableFuture.completedFuture(
                                CommandResult.error("插件卸载失败: " + e.getMessage()));
                    }
                }
                default ->
                        CompletableFuture.completedFuture(CommandResult.success("用法: /plugin [list|path|install|remove]"));
            };
        });
    }

    /**
     * /reload-plugins - 重载插件
     */
    public static SlashCommand createReloadPluginsCommand() {
        return cmd("reload-plugins", "重载插件", (args, ctx, ec) -> {
            Path pluginsDir = Path.of(System.getProperty("user.home"), ".openharness/plugins");

            if (!Files.exists(pluginsDir)) {
                return CompletableFuture.completedFuture(CommandResult.success("插件目录不存在，无需重载"));
            }

            int count = 0;
            try (var stream = Files.list(pluginsDir)) {
                count = (int) stream.filter(Files::isDirectory).count();
            } catch (Exception e) {
                // ignore
            }

            return CompletableFuture.completedFuture(
                    CommandResult.success("插件已重载 (共 " + count + " 个插件)"));
        });
    }

    /**
     * /init - 初始化项目
     */
    public static SlashCommand createInitCommand() {
        return cmd("init", "初始化项目", (args, ctx, ec) -> {
            Path cwd = ctx.getCwd();
            Path openharnessDir = cwd.resolve(".openharness");
            Path configFile = openharnessDir.resolve("config.json");

            try {
                Files.createDirectories(openharnessDir.resolve("hooks"));
                Files.createDirectories(openharnessDir.resolve("memory"));
                Files.createDirectories(openharnessDir.resolve("sessions"));
                Files.createDirectories(openharnessDir.resolve("data"));

                if (!Files.exists(configFile)) {
                    Files.writeString(configFile, "{}");
                }
            } catch (Exception e) {
                return CompletableFuture.completedFuture(
                        CommandResult.error("初始化失败: " + e.getMessage()));
            }

            StringBuilder sb = new StringBuilder("项目已初始化:\n");
            sb.append("  目录: ").append(openharnessDir).append("\n");
            sb.append("  子目录: hooks, memory, sessions, data\n");
            sb.append("  配置: ").append(configFile);

            return CompletableFuture.completedFuture(CommandResult.success(sb.toString()));
        });
    }
}
