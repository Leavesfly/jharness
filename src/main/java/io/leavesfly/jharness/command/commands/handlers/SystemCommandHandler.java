package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.core.engine.CostTracker;
import io.leavesfly.jharness.core.engine.QueryEngine;
import io.leavesfly.jharness.agent.hooks.HookRegistry;
import io.leavesfly.jharness.core.MemoryManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 工具和系统命令 Handler
 * 处理: /memory, /usage, /cost, /stats, /hooks, /vim, /voice
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

            int msgCount = engine.getMessages().size();
            CostTracker tracker = engine.getCostTracker();

            StringBuilder sb = new StringBuilder("会话统计:\n");
            sb.append("  消息数: ").append(msgCount).append("\n");

            if (tracker != null) {
                sb.append("  请求数: ").append(tracker.getRequestCount()).append("\n");
                sb.append("  总 token: ").append(tracker.getTotalTokens());
            }

            int toolCalls = 0;
            for (var msg : engine.getMessages()) {
                toolCalls += msg.getToolUses().size();
            }
            sb.append("\n  工具调用: ").append(toolCalls);

            return CompletableFuture.completedFuture(CommandResult.success(sb.toString()));
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
            if (joined.isEmpty() || "show".equals(joined)) {
                return CompletableFuture.completedFuture(CommandResult.success("Vim 模式: 暂未实现"));
            }

            return CompletableFuture.completedFuture(CommandResult.success("Vim 模式功能待完善"));
        });
    }

    /**
     * /voice - 语音模式
     */
    public static SlashCommand createVoiceCommand() {
        return cmd("voice", "语音模式", (args, ctx, ec) -> {
            String joined = joinArgs(args);
            if (joined.isEmpty() || "show".equals(joined)) {
                return CompletableFuture.completedFuture(
                        CommandResult.success("语音模式: 未实现\n当前不支持语音输入/输出"));
            }

            return CompletableFuture.completedFuture(CommandResult.success("语音模式功能待完善"));
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
