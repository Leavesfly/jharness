package io.leavesfly.jharness.commands.handlers;

import io.leavesfly.jharness.commands.CommandContext;
import io.leavesfly.jharness.commands.CommandResult;
import io.leavesfly.jharness.commands.SlashCommand;
import io.leavesfly.jharness.commands.SimpleSlashCommand;
import io.leavesfly.jharness.services.CronRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Cron 命令处理器
 * 处理: /cron
 */
public class CronCommandHandler {

    private static String joinArgs(List<String> args) {
        return args == null || args.isEmpty() ? "" : String.join(" ", args);
    }

    /**
     * /cron - Cron 作业管理
     */
    public static SlashCommand createCronCommand(CronRegistry cronRegistry) {
        return new SimpleSlashCommand("cron", "Cron 作业管理", (args, ctx, ec) -> {
            String joined = joinArgs(args);
            String[] parts = joined.isEmpty() ? new String[0] : joined.split("\\s+");
            String subcmd = parts.length > 0 ? parts[0] : "list";

            CommandResult result = switch (subcmd) {
                case "list", "ls" -> CommandResult.success(cronRegistry.getSummary());
                case "show" -> {
                    if (parts.length < 2) {
                        yield CommandResult.error("用法: /cron show <name>");
                    }
                    Map<String, Object> job = cronRegistry.getJob(parts[1]);
                    if (job == null) {
                        yield CommandResult.error("作业不存在: " + parts[1]);
                    }
                    yield CommandResult.success(formatJobDetail(job));
                }
                default -> CommandResult.error("未知子命令: " + subcmd + "\n可用: list, show");
            };
            return CompletableFuture.completedFuture(result);
        });
    }

    private static String formatJobDetail(Map<String, Object> job) {
        StringBuilder sb = new StringBuilder();
        sb.append("作业详情:\n");
        sb.append("  名称: ").append(job.get("name")).append("\n");
        sb.append("  调度: ").append(job.getOrDefault("schedule", "N/A")).append("\n");
        sb.append("  命令: ").append(job.get("command")).append("\n");
        if (job.containsKey("cwd")) {
            sb.append("  工作目录: ").append(job.get("cwd")).append("\n");
        }
        sb.append("  状态: ").append(Boolean.TRUE.equals(job.get("enabled")) ? "enabled" : "disabled").append("\n");
        if (job.containsKey("created_at")) {
            sb.append("  创建时间: ").append(job.get("created_at")).append("\n");
        }
        if (job.containsKey("last_executed") && job.get("last_executed") != null) {
            sb.append("  最后执行: ").append(job.get("last_executed")).append("\n");
        }
        if (job.containsKey("execution_count")) {
            sb.append("  执行次数: ").append(job.get("execution_count")).append("\n");
        }
        return sb.toString();
    }
}
