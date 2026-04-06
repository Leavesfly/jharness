package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.services.CronRegistry;
import io.leavesfly.jharness.tools.input.CronListToolInput;

import java.util.concurrent.CompletableFuture;

/**
 * 列出所有已注册的定时作业
 * 
 * 此工具显示所有已注册的 cron 作业定义及其状态。
 */
public class CronListTool extends BaseTool<CronListToolInput> {
    private final CronRegistry cronRegistry;

    public CronListTool(CronRegistry cronRegistry) {
        this.cronRegistry = cronRegistry;
    }

    @Override
    public String getName() {
        return "cron_list";
    }

    @Override
    public String getDescription() {
        return "List all registered cron job definitions with their status.";
    }

    @Override
    public Class<CronListToolInput> getInputClass() {
        return CronListToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(CronListToolInput input, ToolExecutionContext context) {
        try {
            String summary = cronRegistry.getSummary();
            return CompletableFuture.completedFuture(ToolResult.success(summary));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                ToolResult.error("Failed to list cron jobs: " + e.getMessage())
            );
        }
    }

    @Override
    public boolean isReadOnly(CronListToolInput input) {
        return true;
    }
}
