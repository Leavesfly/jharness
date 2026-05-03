package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.core.Settings;

import io.leavesfly.jharness.extension.plugins.PluginInstaller;
import io.leavesfly.jharness.extension.plugins.PluginPaths;
import io.leavesfly.jharness.extension.plugins.marketplace.MarketplaceDefinition;
import io.leavesfly.jharness.extension.plugins.marketplace.MarketplaceRegistry;
import io.leavesfly.jharness.extension.plugins.trust.TrustStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 扩展配置命令 Handler
 * 处理: /effort, /passes, /fast, /plugin, /reload-plugins, /init
 */
public class ConfigPlusCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(ConfigPlusCommandHandler.class);

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
     * /plugin - 管理插件（含 marketplace 子命令）。
     *
     * <p>用法：
     * <ul>
     *   <li>{@code /plugin list|ls} — 列出所有已安装插件（合并用户目录和 Claude 兼容目录）</li>
     *   <li>{@code /plugin path} — 打印插件目录</li>
     *   <li>{@code /plugin install <dir>} — 从本地目录安装</li>
     *   <li>{@code /plugin remove <name>} — 卸载插件</li>
     *   <li>{@code /plugin marketplace add <dir> [name]} — 注册本地 marketplace</li>
     *   <li>{@code /plugin marketplace remove <name>} — 注销 marketplace</li>
     *   <li>{@code /plugin marketplace list} — 列出已注册 marketplace</li>
     *   <li>{@code /plugin marketplace plugins <name>} — 列出 marketplace 内插件</li>
     *   <li>{@code /plugin install <pluginName>@<marketplace>} — 从 marketplace 安装</li>
     * </ul>
     */
    public static SlashCommand createPluginCommand() {
        return cmd("plugin", "管理插件", (args, ctx, ec) -> {
            String joined = joinArgs(args);
            String[] parts = joined.isEmpty() ? new String[0] : joined.split("\\s+");
            String subcmd = parts.length > 0 ? parts[0] : "list";

            // 【修复】原来硬编码 ~/.openharness/plugins 与实际加载路径 ~/.jharness/plugins 不一致，
            // 改为统一使用 PluginPaths.getUserPluginsDir()。
            Path pluginsDir = PluginPaths.getUserPluginsDir();

            return switch (subcmd) {
                case "list", "ls" -> {
                    StringBuilder sb = new StringBuilder("已安装的插件:\n");
                    int total = 0;
                    for (Path root : PluginPaths.listUserPluginRoots()) {
                        try (var stream = Files.list(root)) {
                            var dirs = stream.filter(Files::isDirectory).sorted().toList();
                            if (!dirs.isEmpty()) {
                                sb.append("[").append(root).append("]\n");
                                for (Path d : dirs) {
                                    sb.append("  - ").append(d.getFileName()).append("\n");
                                    total++;
                                }
                            }
                        } catch (Exception e) {
                            sb.append("  读取 ").append(root).append(" 失败: ").append(e.getMessage()).append("\n");
                        }
                    }
                    if (total == 0) {
                        yield CompletableFuture.completedFuture(CommandResult.success("没有已安装的插件"));
                    }
                    yield CompletableFuture.completedFuture(CommandResult.success(sb.toString().trim()));
                }
                case "path" ->
                        CompletableFuture.completedFuture(CommandResult.success("插件目录: " + pluginsDir));
                case "install" -> handleInstall(parts);
                case "remove" -> handleRemove(parts);
                case "marketplace", "mp" -> handleMarketplace(parts);
                case "trust" -> handleTrust(parts);
                default ->
                        CompletableFuture.completedFuture(CommandResult.success(
                                "用法: /plugin [list|path|install|remove|marketplace|trust ...]"));
            };
        });
    }

    /**
     * 【P3】/plugin trust 子命令：管理 author 信任列表。
     * <ul>
     *   <li>{@code /plugin trust list} — 列出已信任的 author</li>
     *   <li>{@code /plugin trust add <author>} — 添加信任</li>
     *   <li>{@code /plugin trust remove <author>} — 撤销信任</li>
     * </ul>
     */
    private static CompletableFuture<CommandResult> handleTrust(String[] parts) {
        TrustStore store = new TrustStore();
        String action = parts.length >= 2 ? parts[1] : "list";
        switch (action) {
            case "add":
                if (parts.length < 3) {
                    return CompletableFuture.completedFuture(
                            CommandResult.error("用法: /plugin trust add <author>"));
                }
                boolean added = store.trust(parts[2]);
                return CompletableFuture.completedFuture(
                        CommandResult.success(added
                                ? "已信任 author: " + parts[2]
                                : "author 已在信任列表中: " + parts[2]));
            case "remove":
            case "rm":
                if (parts.length < 3) {
                    return CompletableFuture.completedFuture(
                            CommandResult.error("用法: /plugin trust remove <author>"));
                }
                boolean removed = store.revoke(parts[2]);
                return CompletableFuture.completedFuture(removed
                        ? CommandResult.success("已撤销信任: " + parts[2])
                        : CommandResult.error("author 不在信任列表: " + parts[2]));
            case "list":
            case "ls":
            default:
                var trusted = store.list();
                if (trusted.isEmpty()) {
                    return CompletableFuture.completedFuture(
                            CommandResult.success("信任列表为空"));
                }
                StringBuilder sb = new StringBuilder("已信任的 author:\n");
                for (String a : trusted) sb.append("  - ").append(a).append('\n');
                return CompletableFuture.completedFuture(
                        CommandResult.success(sb.toString().trim()));
        }
    }

    /**
     * /plugin install 的子逻辑，支持本地目录与 marketplace 引用两种源。
     */
    private static CompletableFuture<CommandResult> handleInstall(String[] parts) {
        if (parts.length < 2) {
            return CompletableFuture.completedFuture(CommandResult.error(
                    "用法: /plugin install <目录> 或 /plugin install <pluginName>@<marketplace>"));
        }
        String ref = parts[1];
        try {
            if (ref.contains("@")) {
                // marketplace 引用：name@marketplace
                int at = ref.lastIndexOf('@');
                String pluginName = ref.substring(0, at);
                String marketplaceName = ref.substring(at + 1);
                if (pluginName.isBlank() || marketplaceName.isBlank()) {
                    return CompletableFuture.completedFuture(
                            CommandResult.error("非法的 marketplace 引用: " + ref));
                }
                MarketplaceRegistry registry = new MarketplaceRegistry();
                Path source = registry.resolvePluginSource(marketplaceName, pluginName);
                boolean success = PluginInstaller.installPlugin(source);
                return CompletableFuture.completedFuture(
                        success ? CommandResult.success(
                                "插件安装成功: " + pluginName + " (来自 marketplace " + marketplaceName + ")")
                                : CommandResult.error("插件安装失败"));
            }
            Path sourceDir = Path.of(ref);
            boolean success = PluginInstaller.installPlugin(sourceDir);
            return CompletableFuture.completedFuture(
                    success ? CommandResult.success("插件安装成功: " + ref)
                            : CommandResult.error("插件安装失败"));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    CommandResult.error("插件安装失败: " + e.getMessage()));
        }
    }

    private static CompletableFuture<CommandResult> handleRemove(String[] parts) {
        if (parts.length < 2) {
            return CompletableFuture.completedFuture(CommandResult.error("用法: /plugin remove <名称>"));
        }
        try {
            boolean success = PluginInstaller.uninstallPlugin(parts[1]);
            return CompletableFuture.completedFuture(
                    success ? CommandResult.success("插件已卸载: " + parts[1])
                            : CommandResult.error("插件不存在: " + parts[1]));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    CommandResult.error("插件卸载失败: " + e.getMessage()));
        }
    }

    /**
     * /plugin marketplace ... 子命令分发。
     */
    private static CompletableFuture<CommandResult> handleMarketplace(String[] parts) {
        MarketplaceRegistry registry = new MarketplaceRegistry();
        String action = parts.length >= 2 ? parts[1] : "list";
        try {
            return switch (action) {
                case "add" -> {
                    if (parts.length < 3) {
                        yield CompletableFuture.completedFuture(CommandResult.error(
                                "用法: /plugin marketplace add <dir> [name]"));
                    }
                    Path dir = Path.of(parts[2]);
                    String name = parts.length >= 4 ? parts[3] : null;
                    String finalName = registry.addFromLocal(name, dir);
                    yield CompletableFuture.completedFuture(
                            CommandResult.success("已添加 marketplace: " + finalName));
                }
                case "remove", "rm" -> {
                    if (parts.length < 3) {
                        yield CompletableFuture.completedFuture(CommandResult.error(
                                "用法: /plugin marketplace remove <name>"));
                    }
                    boolean removed = registry.remove(parts[2]);
                    yield CompletableFuture.completedFuture(removed
                            ? CommandResult.success("已删除 marketplace: " + parts[2])
                            : CommandResult.error("marketplace 不存在: " + parts[2]));
                }
                case "list", "ls" -> {
                    Map<String, Object> desc = registry.describe();
                    if (desc.isEmpty()) {
                        yield CompletableFuture.completedFuture(
                                CommandResult.success("没有已注册的 marketplace"));
                    }
                    StringBuilder sb = new StringBuilder("已注册的 marketplace:\n");
                    desc.forEach((k, v) -> sb.append("  - ").append(k).append(": ").append(v).append("\n"));
                    yield CompletableFuture.completedFuture(
                            CommandResult.success(sb.toString().trim()));
                }
                case "plugins" -> {
                    if (parts.length < 3) {
                        yield CompletableFuture.completedFuture(CommandResult.error(
                                "用法: /plugin marketplace plugins <name>"));
                    }
                    List<MarketplaceDefinition.PluginRef> plugins = registry.listPlugins(parts[2]);
                    if (plugins.isEmpty()) {
                        yield CompletableFuture.completedFuture(CommandResult.success(
                                "marketplace " + parts[2] + " 无插件或不可用"));
                    }
                    StringBuilder sb = new StringBuilder("marketplace 插件列表:\n");
                    for (MarketplaceDefinition.PluginRef p : plugins) {
                        sb.append("  - ").append(p.getName());
                        if (p.getDescription() != null) sb.append(" — ").append(p.getDescription());
                        sb.append(" [source=").append(p.getSource()).append("]\n");
                    }
                    yield CompletableFuture.completedFuture(
                            CommandResult.success(sb.toString().trim()));
                }
                default -> CompletableFuture.completedFuture(CommandResult.success(
                        "用法: /plugin marketplace [add|remove|list|plugins]"));
            };
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    CommandResult.error("marketplace 操作失败: " + e.getMessage()));
        }
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
            } catch (java.io.IOException e) {
                // 权限不足 / 目录被并发删除等异常：不终止命令执行，但必须有日志痕迹
                logger.debug("枚举插件目录失败: {}", pluginsDir, e);
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
