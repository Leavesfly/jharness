package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.integration.CronRegistry;
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

        // 安全加固：入库前必须过危险命令黑名单，避免绕过 bash 工具通过 cron 注入任意命令。
        // 这里复用 BashTool 的统一检测逻辑，后续 RemoteTrigger 执行时也会再次校验。
        String dangerous = BashTool.detectDangerousCommand(input.getCommand());
        if (dangerous != null) {
            return CompletableFuture.completedFuture(
                ToolResult.error("安全限制: cron 作业命令被危险命令黑名单拦截")
            );
        }
        if (input.getCommand().length() > 10000) {
            return CompletableFuture.completedFuture(
                ToolResult.error("安全限制: cron 命令长度超过 10000 字符")
            );
        }

        try {
            Map<String, Object> job = new HashMap<>();
            job.put("name", input.getName());
            job.put("schedule", input.getSchedule());
            job.put("command", input.getCommand());
            if (input.getCwd() != null && !input.getCwd().isEmpty()) {
                // cwd 必须是绝对路径 + 规范化后在项目工作目录内，避免指到 /etc、/var 等敏感目录
                java.nio.file.Path requested = java.nio.file.Paths.get(input.getCwd()).toAbsolutePath().normalize();
                java.nio.file.Path base = context.getCwd().toAbsolutePath().normalize();
                if (!requested.startsWith(base)) {
                    return CompletableFuture.completedFuture(
                        ToolResult.error("安全限制: cron cwd 必须在当前工作目录内")
                    );
                }
                job.put("cwd", requested.toString());
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
