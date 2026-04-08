package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.core.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ConfigCommandHandler {

    private static SlashCommand cmd(String name, String desc, Handler h) {
        return new SimpleSlashCommand(name, desc, (args, ctx, ec) -> CompletableFuture.completedFuture(h.handle(args, ctx)));
    }

    @FunctionalInterface
    interface Handler { CommandResult handle(List<String> args, CommandContext ctx); }

    public static SlashCommand createConfigCommand() {
        return cmd("config", "显示或更新配置", (args, ctx) -> {
            Settings settings = Settings.load();
            String action = args.isEmpty() ? "show" : args.get(0);
            if ("show".equals(action)) return CommandResult.success(settings.toJson());
            if ("set".equals(action) && args.size() >= 3 && settings.set(args.get(1), args.get(2))) { settings.save(); return CommandResult.success("已更新: " + args.get(1)); }
            return CommandResult.success("用法: /config [show|set KEY VALUE]");
        });
    }

    public static SlashCommand createModelCommand() {
        return cmd("model", "显示或设置模型", (args, ctx) -> {
            Settings s = Settings.load();
            if (args.isEmpty() || "show".equals(args.get(0))) return CommandResult.success("当前模型: " + s.getModel());
            if ("set".equals(args.get(0)) && args.size() >= 2) { s.setModel(args.get(1)); s.save(); return CommandResult.success("模型: " + args.get(1)); }
            return CommandResult.success("用法: /model [show|set MODEL]");
        });
    }

    public static SlashCommand createPermissionsCommand() {
        return cmd("permissions", "显示或设置权限模式", (args, ctx) -> {
            Settings s = Settings.load();
            if (args.isEmpty() || "show".equals(args.get(0))) return CommandResult.success("权限: " + s.getPermissionMode() + "\n允许: " + s.getAllowedTools() + "\n禁止: " + s.getDeniedTools());
            if ("set".equals(args.get(0)) && args.size() >= 2 && s.setPermissionMode(args.get(1))) { s.save(); return CommandResult.success("权限: " + args.get(1)); }
            return CommandResult.success("用法: /permissions [show|set MODE]");
        });
    }

    public static SlashCommand createPlanCommand() {
        return cmd("plan", "切换计划模式", (args, ctx) -> {
            Settings s = Settings.load();
            String action = args.isEmpty() ? "toggle" : args.get(0);
            if ("on".equals(action) || "enter".equals(action)) { s.setPermissionMode("plan"); s.save(); return CommandResult.success("计划模式已启用"); }
            if ("off".equals(action) || "exit".equals(action)) { s.setPermissionMode("default"); s.save(); return CommandResult.success("计划模式已禁用"); }
            String cur = s.getPermissionMode().name().toLowerCase(); String next = "plan".equals(cur) ? "default" : "plan";
            s.setPermissionMode(next); s.save(); return CommandResult.success("计划模式: " + ("plan".equals(next) ? "已启用" : "已禁用"));
        });
    }

    public static SlashCommand createSkillsCommand() {
        return cmd("skills", "列出或显示技能", (args, ctx) -> {
            Path dir = Paths.get(System.getProperty("user.dir"), ".jharness", "skills");
            List<String> skills = new ArrayList<>();
            if (Files.exists(dir)) try { Files.list(dir).filter(Files::isDirectory).forEach(d -> skills.add(d.getFileName().toString())); } catch (Exception e) {}
            if (args.isEmpty() || "list".equals(args.get(0))) return skills.isEmpty() ? CommandResult.success("暂无技能") : CommandResult.success("技能:\n" + String.join("\n", skills));
            if ("show".equals(args.get(0)) && args.size() >= 2) { Path f = dir.resolve(args.get(1)).resolve("SKILL.md"); if (Files.exists(f)) try { return CommandResult.success(Files.readString(f)); } catch (Exception e) { return CommandResult.error("读取失败"); } return CommandResult.error("未找到: " + args.get(1)); }
            return CommandResult.success("用法: /skills [list|show NAME]");
        });
    }

    public static SlashCommand createTasksCommand() { return cmd("tasks", "管理后台任务", (a, c) -> CommandResult.success("用法: /tasks [list|run CMD|stop ID|show ID]")); }
    public static SlashCommand createMcpCommand() { return cmd("mcp", "显示 MCP 状态", (a, c) -> CommandResult.success("MCP: " + Settings.load().getMcpServers())); }

    public static SlashCommand createLoginCommand() {
        return cmd("login", "认证或存储 API Key", (args, ctx) -> {
            Settings s = Settings.load();
            if (args.isEmpty()) { String m = s.getApiKey() != null && s.getApiKey().length() > 10 ? s.getApiKey().substring(0,6)+"..."+s.getApiKey().substring(s.getApiKey().length()-4) : "(未配置)"; return CommandResult.success("Provider: " + s.getProvider() + "\n模型: " + s.getModel() + "\nKey: " + m + "\n用法: /login API_KEY"); }
            s.setApiKey(args.get(0)); s.save(); return CommandResult.success("API Key 已保存");
        });
    }

    public static SlashCommand createLogoutCommand() { return cmd("logout", "清除 API Key", (a, c) -> { Settings s = Settings.load(); s.setApiKey(""); s.save(); return CommandResult.success("API Key 已清除"); }); }
    public static SlashCommand createThemeCommand() { return cmd("theme", "显示或设置主题", (a, c) -> { Settings s = Settings.load(); if (a.isEmpty() || "show".equals(a.get(0))) return CommandResult.success("主题: " + s.getTheme()); if ("set".equals(a.get(0)) && a.size() >= 2) { s.setTheme(a.get(1)); s.save(); return CommandResult.success("主题: " + a.get(1)); } return CommandResult.success("用法: /theme [show|set THEME]"); }); }

    public static SlashCommand createDoctorCommand() {
        return cmd("doctor", "环境诊断", (a, c) -> {
            Settings s = Settings.load();
            return CommandResult.success("Java: " + System.getProperty("java.version") + "\nOS: " + System.getProperty("os.name") + "\n目录: " + System.getProperty("user.dir") + "\n模型: " + s.getModel() + "\nProvider: " + s.getProvider() + "\n权限: " + s.getPermissionMode() + "\nKey: " + (s.getApiKey() != null && !s.getApiKey().isEmpty() ? "已配置" : "未配置"));
        });
    }
}
