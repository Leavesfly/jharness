package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tasks.BackgroundTaskManager;
import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.input.TaskGetToolInput;
import io.leavesfly.jharness.tasks.TaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 任务查询工具
 */
public class TaskGetTool extends BaseTool<TaskGetToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(TaskGetTool.class);
    private final BackgroundTaskManager taskManager;

    public TaskGetTool(BackgroundTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String getName() {
        return "task_get";
    }

    @Override
    public String getDescription() {
        return "获取特定后台任务的详细信息";
    }

    @Override
    public Class<TaskGetToolInput> getInputClass() {
        return TaskGetToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(TaskGetToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                TaskRecord task = taskManager.getTask(input.getTaskId());
                if (task == null) {
                    return ToolResult.error("任务不存在: " + input.getTaskId());
                }

                StringBuilder sb = new StringBuilder();
                sb.append("任务详情:\n");
                sb.append("  ID: ").append(task.getId()).append("\n");
                sb.append("  描述: ").append(task.getDescription()).append("\n");
                sb.append("  状态: ").append(task.getStatus()).append("\n");
                sb.append("  命令: ").append(task.getCommand()).append("\n");
                sb.append("  工作目录: ").append(task.getCwd()).append("\n");
                if (task.getStartedAt() != null) {
                    sb.append("  开始时间: ").append(task.getStartedAt()).append("\n");
                }
                if (task.getEndedAt() != null) {
                    sb.append("  结束时间: ").append(task.getEndedAt()).append("\n");
                }
                if (task.getExitCode() != null) {
                    sb.append("  退出码: ").append(task.getExitCode()).append("\n");
                }

                return ToolResult.success(sb.toString().trim());
            } catch (Exception e) {
                logger.error("获取任务失败", e);
                return ToolResult.error("获取任务失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(TaskGetToolInput input) {
        return true;
    }
}
