package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.session.permissions.PermissionChecker;
import io.leavesfly.jharness.session.permissions.PermissionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 配置相关的斜杠命令：/config、/model、/permissions、/plan、/skills、/tasks、/mcp、/login、/logout、/theme、/doctor。
 *
 * 重构说明：
 * - 拆分旧的"一行塞多条 if+语句"风格，转为可读的多行分支，避免了后续维护时的误读；
 * - 统一处理空 catch：至少记录 debug 日志，保留原逻辑但把吞异常换成"有痕迹"的降级；
 * - API Key 脱敏逻辑抽到 {@link #maskApiKey(String)}，便于复用与测试。
 */
public class ConfigCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(ConfigCommandHandler.class);

    private static SlashCommand cmd(String name, String desc, Handler h) {
        return new SimpleSlashCommand(name, desc,
                (args, ctx, ec) -> CompletableFuture.completedFuture(h.handle(args, ctx)));
    }

    @FunctionalInterface
    interface Handler {
        CommandResult handle(List<String> args, CommandContext ctx);
    }

    public static SlashCommand createConfigCommand() {
        return cmd("config", "显示或更新配置", (args, ctx) -> {
            Settings settings = Settings.load();
            String action = args.isEmpty() ? "show" : args.get(0);
            if ("show".equals(action)) {
                return CommandResult.success(settings.toJson());
            }
            if ("set".equals(action) && args.size() >= 3 && settings.set(args.get(1), args.get(2))) {
                settings.save();
                return CommandResult.success("已更新: " + args.get(1));
            }
            return CommandResult.success("用法: /config [show|set KEY VALUE]");
        });
    }

    public static SlashCommand createModelCommand() {
        return cmd("model", "显示或设置模型", (args, ctx) -> {
            Settings s = Settings.load();
            if (args.isEmpty() || "show".equals(args.get(0))) {
                return CommandResult.success("当前模型: " + s.getModel());
            }
            if ("set".equals(args.get(0)) && args.size() >= 2) {
                s.setModel(args.get(1));
                s.save();
                return CommandResult.success("模型: " + args.get(1));
            }
            return CommandResult.success("用法: /model [show|set MODEL]");
        });
    }

    public static SlashCommand createPermissionsCommand() {
        return cmd("permissions", "显示或设置权限模式", (args, ctx) -> {
            // FP-2：/permissions set 必须同时更新运行时 PermissionChecker，否则 Settings 与
            // 真正在工作的 PermissionChecker 状态漂移，模式切换无法生效。
            Settings s = ctx != null && ctx.getSettings() != null ? ctx.getSettings() : Settings.load();
            if (args.isEmpty() || "show".equals(args.get(0))) {
                return CommandResult.success(
                        "权限: " + s.getPermissionMode()
                                + "\n允许: " + s.getAllowedTools()
                                + "\n禁止: " + s.getDeniedTools());
            }
            if ("set".equals(args.get(0)) && args.size() >= 2 && s.setPermissionMode(args.get(1))) {
                syncRuntimeMode(ctx, s.getPermissionMode());
                s.save();
                return CommandResult.success("权限: " + args.get(1));
            }
            return CommandResult.success("用法: /permissions [show|set MODE]");
        });
    }

    public static SlashCommand createPlanCommand() {
        return cmd("plan", "切换计划模式", (args, ctx) -> {
            // FP-2：/plan on/off/toggle 必须同步 PermissionChecker 的 mode，否则用户以为进入
            // 计划模式但写操作依然会被执行。
            Settings s = ctx != null && ctx.getSettings() != null ? ctx.getSettings() : Settings.load();
            String action = args.isEmpty() ? "toggle" : args.get(0);
            if ("on".equals(action) || "enter".equals(action)) {
                s.setPermissionMode("plan");
                syncRuntimeMode(ctx, PermissionMode.PLAN);
                s.save();
                return CommandResult.success("计划模式已启用");
            }
            if ("off".equals(action) || "exit".equals(action)) {
                s.setPermissionMode("default");
                syncRuntimeMode(ctx, PermissionMode.DEFAULT);
                s.save();
                return CommandResult.success("计划模式已禁用");
            }
            String cur = s.getPermissionMode().name().toLowerCase();
            String next = "plan".equals(cur) ? "default" : "plan";
            s.setPermissionMode(next);
            syncRuntimeMode(ctx, s.getPermissionMode());
            s.save();
            return CommandResult.success("计划模式: " + ("plan".equals(next) ? "已启用" : "已禁用"));
        });
    }

    /**
     * FP-2：把 Settings 中的权限模式切换同步到运行时 PermissionChecker。
     * 若上下文里没有 PermissionChecker（比如单元测试场景），记录 debug 日志后降级。
     */
    private static void syncRuntimeMode(CommandContext ctx, PermissionMode mode) {
        if (ctx == null) {
            return;
        }
        PermissionChecker checker = ctx.getPermissionChecker();
        if (checker == null) {
            logger.debug("未注入运行时 PermissionChecker，模式切换 {} 仅落到 Settings", mode);
            return;
        }
        checker.setMode(mode);
    }

    public static SlashCommand createSkillsCommand() {
        return cmd("skills", "列出或显示技能", (args, ctx) -> {
            Path dir = Paths.get(System.getProperty("user.dir"), ".jharness", "skills");
            List<String> skills = listSkillNames(dir);

            if (args.isEmpty() || "list".equals(args.get(0))) {
                return skills.isEmpty()
                        ? CommandResult.success("暂无技能")
                        : CommandResult.success("技能:\n" + String.join("\n", skills));
            }
            if ("show".equals(args.get(0)) && args.size() >= 2) {
                Path skillFile = dir.resolve(args.get(1)).resolve("SKILL.md");
                if (!Files.exists(skillFile)) {
                    return CommandResult.error("未找到: " + args.get(1));
                }
                try {
                    return CommandResult.success(Files.readString(skillFile));
                } catch (IOException e) {
                    logger.warn("读取技能文件失败: {}", skillFile, e);
                    return CommandResult.error("读取失败: " + e.getMessage());
                }
            }
            return CommandResult.success("用法: /skills [list|show NAME]");
        });
    }

    /** 枚举 skills 目录下的一级子目录作为技能名列表。 */
    private static List<String> listSkillNames(Path dir) {
        List<String> skills = new ArrayList<>();
        if (!Files.exists(dir)) {
            return skills;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .forEach(d -> skills.add(d.getFileName().toString()));
        } catch (IOException e) {
            // 不能直接吞异常：至少留下 debug 痕迹，便于排查目录被删/权限被改等运维问题
            logger.debug("枚举 skills 目录失败: {}", dir, e);
        }
        return skills;
    }

    public static SlashCommand createTasksCommand() {
        return cmd("tasks", "管理后台任务",
                (a, c) -> CommandResult.success("用法: /tasks [list|run CMD|stop ID|show ID]"));
    }

    public static SlashCommand createMcpCommand() {
        return cmd("mcp", "显示 MCP 状态",
                (a, c) -> CommandResult.success("MCP: " + Settings.load().getMcpServers()));
    }

    public static SlashCommand createLoginCommand() {
        return cmd("login", "认证或存储 API Key", (args, ctx) -> {
            Settings s = Settings.load();
            if (args.isEmpty()) {
                return CommandResult.success(
                        "Provider: " + s.getProvider()
                                + "\n模型: " + s.getModel()
                                + "\nKey: " + maskApiKey(s.getApiKey())
                                + "\n用法: /login API_KEY");
            }
            s.setApiKey(args.get(0));
            s.save();
            return CommandResult.success("API Key 已保存");
        });
    }

    public static SlashCommand createLogoutCommand() {
        return cmd("logout", "清除 API Key", (a, c) -> {
            Settings s = Settings.load();
            s.setApiKey("");
            s.save();
            return CommandResult.success("API Key 已清除");
        });
    }

    public static SlashCommand createThemeCommand() {
        return cmd("theme", "显示或设置主题", (a, c) -> {
            Settings s = Settings.load();
            if (a.isEmpty() || "show".equals(a.get(0))) {
                return CommandResult.success("主题: " + s.getTheme());
            }
            if ("set".equals(a.get(0)) && a.size() >= 2) {
                s.setTheme(a.get(1));
                s.save();
                return CommandResult.success("主题: " + a.get(1));
            }
            return CommandResult.success("用法: /theme [show|set THEME]");
        });
    }

    public static SlashCommand createDoctorCommand() {
        return cmd("doctor", "环境诊断", (a, c) -> {
            Settings s = Settings.load();
            String keyStatus = (s.getApiKey() != null && !s.getApiKey().isEmpty()) ? "已配置" : "未配置";
            return CommandResult.success(
                    "Java: " + System.getProperty("java.version")
                            + "\nOS: " + System.getProperty("os.name")
                            + "\n目录: " + System.getProperty("user.dir")
                            + "\n模型: " + s.getModel()
                            + "\nProvider: " + s.getProvider()
                            + "\n权限: " + s.getPermissionMode()
                            + "\nKey: " + keyStatus);
        });
    }

    /**
     * 对 API Key 做前缀 + 后缀保留脱敏（如 sk-abc...xyz9），未配置时返回 "(未配置)"。
     * 抽出为 package 可见静态方法，方便单元测试覆盖。
     */
    static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "(未配置)";
        }
        if (apiKey.length() <= 10) {
            return "***";
        }
        return apiKey.substring(0, 6) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}
