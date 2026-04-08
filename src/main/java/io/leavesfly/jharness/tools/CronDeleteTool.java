package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.integration.CronRegistry;
import io.leavesfly.jharness.tools.input.CronDeleteToolInput;

import java.util.concurrent.CompletableFuture;

/**
 * 删除指定的定时作业
 * 
 * 此工具从 CronRegistry 中移除作业定义。
 */
public class CronDeleteTool extends BaseTool<CronDeleteToolInput> {
    private final CronRegistry cronRegistry;

    public CronDeleteTool(CronRegistry cronRegistry) {
        this.cronRegistry = cronRegistry;
    }

    @Override
    public String getName() {
        return "cron_delete";
    }

    @Override
    public String getDescription() {
        return "Delete a registered cron job definition by name.";
    }

    @Override
    public Class<CronDeleteToolInput> getInputClass() {
        return CronDeleteToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(CronDeleteToolInput input, ToolExecutionContext context) {
        if (input.getName() == null || input.getName().isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Parameter 'name' is required")
            );
        }

        try {
            boolean removed = cronRegistry.deleteJob(input.getName());
            if (removed) {
                return CompletableFuture.completedFuture(
                    ToolResult.success("Cron job '" + input.getName() + "' deleted.")
                );
            } else {
                return CompletableFuture.completedFuture(
                    ToolResult.error("Cron job not found: " + input.getName())
                );
            }
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Failed to delete cron job: " + e.getMessage())
            );
        }
    }

    @Override
    public boolean isReadOnly(CronDeleteToolInput input) {
        return false;
    }
}
