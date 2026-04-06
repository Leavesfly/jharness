package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.services.CronRegistry;
import io.leavesfly.jharness.tools.input.CronCreateToolInput;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 创建或更新定时作业定义
 * 
 * 此工具注册作业到 CronRegistry，作业可以后续通过 remote_trigger 工具按需执行。
 */
public class CronCreateTool extends BaseTool<CronCreateToolInput> {
    private final CronRegistry cronRegistry;

    public CronCreateTool(CronRegistry cronRegistry) {
        this.cronRegistry = cronRegistry;
    }

    @Override
    public String getName() {
        return "cron_create";
    }

    @Override
    public String getDescription() {
        return "Create or update a cron job definition. Jobs can be triggered on-demand using remote_trigger.";
    }

    @Override
    public Class<CronCreateToolInput> getInputClass() {
        return CronCreateToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(CronCreateToolInput input, ToolExecutionContext context) {
        if (input.getName() == null || input.getName().isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Parameter 'name' is required")
            );
        }
        if (input.getSchedule() == null || input.getSchedule().isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Parameter 'schedule' is required")
            );
        }
        if (input.getCommand() == null || input.getCommand().isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Parameter 'command' is required")
            );
        }

        try {
            Map<String, Object> job = new HashMap<>();
            job.put("name", input.getName());
            job.put("schedule", input.getSchedule());
            job.put("command", input.getCommand());
            if (input.getCwd() != null && !input.getCwd().isEmpty()) {
                job.put("cwd", input.getCwd());
            }

            cronRegistry.upsertJob(job);

            String message = String.format(
                "Cron job '%s' created/updated.\nSchedule: %s\nCommand: %s",
                input.getName(), input.getSchedule(), input.getCommand()
            );

            return CompletableFuture.completedFuture(ToolResult.success(message));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Failed to create cron job: " + e.getMessage())
            );
        }
    }

    @Override
    public boolean isReadOnly(CronCreateToolInput input) {
        return false;
    }
}
