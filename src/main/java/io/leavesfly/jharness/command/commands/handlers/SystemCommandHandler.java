package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.core.engine.CostTracker;
import io.leavesfly.jharness.core.engine.QueryEngine;
import io.leavesfly.jharness.core.state.AppState;
import io.leavesfly.jharness.core.state.AppStateStore;
import io.leavesfly.jharness.agent.hooks.HookRegistry;
import io.leavesfly.jharness.core.MemoryManager;
import io.leavesfly.jharness.prompts.outputstyles.OutputStyle;
import io.leavesfly.jharness.prompts.outputstyles.OutputStyleLoader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 工具和系统命令 Handler
 * 处理: /memory, /usage, /cost, /stats, /hooks, /vim, /voice, /output-style
 */
public class SystemCommandHandler {

    private static String joinArgs(List<String> args) {
        return args == null || args.isEmpty() ? "" : String.join(" ", args);
    }

    private static SimpleSlashCommand cmd(String name, String desc, SimpleSlashCommand.CommandHandler handler) {
        return new SimpleSlashCommand(name, desc, handler);
    }

    private static String getProjectName(CommandContext ctx) {
        return ctx.getCwd().getFileName() != null ? ctx.getCwd().getFileName().toString() : "default";
    }

    /**
     * /memory - 项目记忆管理
     */
    public static SlashCommand createMemoryCommand(MemoryManager memoryManager) {
        return cmd("memory", "项目内存", (args, ctx, ec) -> {
            String joined = joinArgs(args);
            String[] parts = joined.isEmpty() ? new String[0] : joined.split("\\s+");
            String subcmd = parts.length > 0 ? parts[0] : "list";
            String project = getProjectName(ctx);

            return switch (subcmd) {
                case "list", "ls" -> listMemories(memoryManager, project);
                case "add" -> {
                    if (parts.length < 2) {
                        yield CompletableFuture.completedFuture(CommandResult.error("用法: /memory add <标题> <内容>"));
                    }
                    String title = parts[1];
                    String content = parts.length > 2 ? String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)) : "";
                    yield addMemory(memoryManager, project, title, content);
                }
                case "remove", "rm" -> {
                    if (parts.length < 2) {
                        yield CompletableFuture.completedFuture(CommandResult.error("用法: /memory remove <标题>"));
                    }
                    yield removeMemory(memoryManager, project, parts[1]);
                }
                default -> listMemories(memoryManager, project);
            };
        });
    }

    /**
     * /usage - 显示 token 使用情况
     */
    public static SlashCommand createUsageCommand() {
        return cmd("usage", "使用情况", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }

            CostTracker tracker = engine.getCostTracker();
            if (tracker == null) {
                return CompletableFuture.completedFuture(CommandResult.error("成本追踪器未初始化"));
            }

            String msg = String.format(
                    "Token 使用情况:\n" +
                    "  请求次数: %d\n" +
                    "  输入 token: %d\n" +
                    "  输出 token: %d\n" +
                    "  缓存读取: %d\n" +
                    "  缓存创建: %d\n" +
                    "  总计: %d",
                    tracker.getRequestCount(),
                    tracker.getTotalInputTokens(),
                    tracker.getTotalOutputTokens(),
                    tracker.getTotalCacheReadTokens(),
                    tracker.getTotalCacheCreationTokens(),
                    tracker.getTotalTokens());
            return CompletableFuture.completedFuture(CommandResult.success(msg));
        });
    }

    /**
     * /cost - 显示估算费用
     */
    public static SlashCommand createCostCommand() {
        return cmd("cost", "Token 费用", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }

            CostTracker tracker = engine.getCostTracker();
            if (tracker == null) {
                return CompletableFuture.completedFuture(CommandResult.error("成本追踪器未初始化"));
            }

            double inputCost = tracker.getTotalInputTokens() / 1_000_000.0 * 3.0;
            double outputCost = tracker.getTotalOutputTokens() / 1_000_000.0 * 15.0;
            double cacheReadCost = tracker.getTotalCacheReadTokens() / 1_000_000.0 * 0.3;
            double cacheCreateCost = tracker.getTotalCacheCreationTokens() / 1_000_000.0 * 3.75;
            double totalCost = inputCost + outputCost + cacheReadCost + cacheCreateCost;

            String msg = String.format(
                    "估算费用 (Claude 3.5 Sonnet):\n" +
                    "  输入: $%.4f (%d token)\n" +
                    "  输出: $%.4f (%d token)\n" +
                    "  缓存读取: $%.4f (%d token)\n" +
                    "  缓存创建: $%.4f (%d token)\n" +
                    "  总计: $%.4f",
                    inputCost, tracker.getTotalInputTokens(),
                    outputCost, tracker.getTotalOutputTokens(),
                    cacheReadCost, tracker.getTotalCacheReadTokens(),
                    cacheCreateCost, tracker.getTotalCacheCreationTokens(),
                    totalCost);
            return CompletableFuture.completedFuture(CommandResult.success(msg));
        });
    }

    /**
     * /stats - 会话统计
     */
    public static SlashCommand createStatsCommand() {
        return cmd("stats", "会话统计", (args, ctx, ec) -> {
            QueryEngine engine = ctx.getEngine();
            if (engine == null) {
                return CompletableFuture.completedFuture(CommandResult.error("查询引擎未初始化"));
            }
    
            Settings settings = ctx.getSettings();
            AppStateStore stateStore = ctx.getAppStateStore();
    
            int msgCount = engine.getMessages().size();
            CostTracker tracker = engine.getCostTracker();
    
            // 工具调用次数
            int toolCalls = 0;
            for (var msg : engine.getMessages()) {
                toolCalls += msg.getToolUses().size();
            }
    
            // 从 AppStateStore 或 Settings 读取状态
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
    
    /**
     * /output-style - 输出样式管理
     */
    public static SlashCommand createOutputStyleCommand() {
        return cmd("output-style", "输出样式", (args, ctx, ec) -> {
            Settings settings = ctx.getSettings();
            AppStateStore stateStore = ctx.getAppStateStore();
    
            List<OutputStyle> styles = OutputStyleLoader.loadOutputStyles();
            Map<String, OutputStyle> available = styles.stream()
                    .collect(Collectors.toMap(OutputStyle::getName, s -> s));
    
            // 读取当前样式
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

    /**
     * /hooks - 查看 hooks
     */
    public static SlashCommand createHooksCommand() {
        return cmd("hooks", "查看 hooks", (args, ctx, ec) -> {
            Path hooksDir = ctx.getCwd().resolve(".openharness/hooks");
            Path globalHooksDir = Path.of(System.getProperty("user.home"), ".openharness/hooks");

            StringBuilder sb = new StringBuilder("Hooks 状态:\n");
            sb.append("  项目 hooks 目录: ").append(hooksDir).append("\n");
            sb.append("  全局 hooks 目录: ").append(globalHooksDir).append("\n");

            if (Files.exists(hooksDir)) {
                sb.append("  项目 hooks: 已配置\n");
                try (var stream = Files.list(hooksDir)) {
                    long count = stream.count();
                    sb.append("    文件数: ").append(count);
                } catch (Exception e) {
                    sb.append("    读取失败");
                }
            } else {
                sb.append("  项目 hooks: 未配置");
            }

            return CompletableFuture.completedFuture(CommandResult.success(sb.toString()));
        });
    }

    /**
     * /hooks - 增强版，使用 HookRegistry
     */
    public static SlashCommand createHooksCommandWithRegistry(HookRegistry hookRegistry) {
        return cmd("hooks", "查看 hooks", (args, ctx, ec) -> {
            String joined = joinArgs(args);
            String[] parts = joined.isEmpty() ? new String[0] : joined.split("\\s+");
            String subcmd = parts.length > 0 ? parts[0] : "status";

            CommandResult result = switch (subcmd) {
                case "status", "list", "ls" ->
                    CommandResult.success(hookRegistry.summary());
                default ->
                    CommandResult.error("未知子命令: " + subcmd + "\n可用: status, list");
            };
            return CompletableFuture.completedFuture(result);
        });
    }

    /**
     * /vim - Vim 模式切换
     */
    public static SlashCommand createVimCommand() {
        return cmd("vim", "Vim 模式", (args, ctx, ec) -> {
            Settings settings = ctx.getSettings();
            if (settings == null) {
                return CompletableFuture.completedFuture(CommandResult.error("设置未初始化"));
            }

            String joined = joinArgs(args);
            AppStateStore stateStore = ctx.getAppStateStore();

            // 读取当前状态
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

    /**
     * 语音能力诊断结果
     */
    public record VoiceDiagnostics(boolean available, String reason, String recorder) {
        public VoiceDiagnostics(boolean available, String reason) {
            this(available, reason, null);
        }
    }

    /**
     * 检测当前环境是否支持语音输入。
     * 依次尝试在 PATH 中查找 sox、ffmpeg、arecord。
     */
    public static VoiceDiagnostics inspectVoiceCapabilities() {
        String[] recorders = {"sox", "ffmpeg", "arecord"};
        for (String rec : recorders) {
            if (findInPath(rec) != null) {
                return new VoiceDiagnostics(true, "voice shell is available", rec);
            }
        }
        return new VoiceDiagnostics(false,
                "no supported recorder found (expected sox, ffmpeg, or arecord)");
    }

    /** 在 PATH 中查找可执行文件，找到则返回其完整路径，找不到返回 null。 */
    private static String findInPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File f = new File(dir, executable);
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * /voice - 语音模式
     */
    public static SlashCommand createVoiceCommand() {
        return cmd("voice", "语音模式", (args, ctx, ec) -> {
            Settings settings = ctx.getSettings();
            AppStateStore stateStore = ctx.getAppStateStore();

            VoiceDiagnostics diagnostics = inspectVoiceCapabilities();

            // 读取当前状态
            boolean current = settings != null && settings.isVoiceEnabled();
            if (stateStore != null) {
                current = stateStore.get().isVoiceEnabled();
            }

            String joined = joinArgs(args);
            String[] parts = joined.split("\\s+", 2);
            String subcmd = parts[0];

            if (joined.isEmpty() || "show".equals(subcmd)) {
                return CompletableFuture.completedFuture(CommandResult.success(
                        "语音模式: " + (current ? "on" : "off") + "\n"
                        + "可用: " + (diagnostics.available() ? "yes" : "no") + "\n"
                        + "原因: " + diagnostics.reason()
                        + (diagnostics.recorder() != null ? "\n录音器: " + diagnostics.recorder() : "")));
            }

            if ("keyterms".equals(subcmd)) {
                String text = parts.length > 1 ? parts[1].trim() : "";
                if (text.isEmpty()) {
                    return CompletableFuture.completedFuture(
                            CommandResult.error("用法: /voice keyterms <文本>"));
                }
                // 简单分词提取关键词（去除常用停用词）
                String[] words = text.split("\\s+");
                List<String> stopWords = List.of("the", "a", "an", "is", "in", "on", "at",
                        "to", "of", "and", "or", "for", "的", "了", "在", "是", "和");
                String keyterms = Arrays.stream(words)
                        .filter(w -> w.length() > 2 && !stopWords.contains(w.toLowerCase()))
                        .distinct()
                        .limit(10)
                        .collect(Collectors.joining(", "));
                return CompletableFuture.completedFuture(
                        CommandResult.success("关键词: " + (keyterms.isEmpty() ? "(无)": keyterms)));
            }

            boolean newValue;
            switch (subcmd) {
                case "on"     -> newValue = true;
                case "off"    -> newValue = false;
                case "toggle" -> newValue = !current;
                default -> {
                    return CompletableFuture.completedFuture(
                            CommandResult.error("用法: /voice [show|on|off|toggle|keyterms <文本>]"));
                }
            }

            if (newValue && !diagnostics.available()) {
                return CompletableFuture.completedFuture(
                        CommandResult.error("无法开启语音模式: " + diagnostics.reason()));
            }

            if (settings != null) {
                settings.setVoiceEnabled(newValue);
                settings.save();
            }
            if (stateStore != null) {
                boolean finalNewValue = newValue;
                stateStore.set(s -> {
                    s.setVoiceEnabled(finalNewValue);
                    s.setVoiceAvailable(diagnostics.available());
                    s.setVoiceReason(diagnostics.reason());
                });
            }

            return CompletableFuture.completedFuture(
                    CommandResult.success("语音模式已" + (newValue ? "开启" : "关闭")));
        });
    }

    // === Memory 操作 ===

    private static CompletableFuture<CommandResult> listMemories(MemoryManager memoryManager, String project) {
        List<String> memories = memoryManager.listMemories(project);
        if (memories.isEmpty()) {
            return CompletableFuture.completedFuture(CommandResult.success("没有已保存的记忆"));
        }

        StringBuilder sb = new StringBuilder("项目记忆:\n");
        for (String title : memories) {
            sb.append("  - ").append(title).append("\n");
        }

        return CompletableFuture.completedFuture(CommandResult.success(sb.toString().trim()));
    }

    private static CompletableFuture<CommandResult> addMemory(MemoryManager memoryManager, String project,
                                                               String title, String content) {
        memoryManager.addMemory(project, title, content);
        return CompletableFuture.completedFuture(
                CommandResult.success("记忆已添加: " + title));
    }

    private static CompletableFuture<CommandResult> removeMemory(MemoryManager memoryManager, String project,
                                                                  String title) {
        boolean removed = memoryManager.removeMemory(project, title);
        if (removed) {
            return CompletableFuture.completedFuture(CommandResult.success("记忆已删除: " + title));
        } else {
            return CompletableFuture.completedFuture(CommandResult.error("未找到记忆: " + title));
        }
    }
}
