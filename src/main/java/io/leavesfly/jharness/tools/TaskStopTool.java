package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.agent.tasks.BackgroundTaskManager;
import io.leavesfly.jharness.tools.input.TaskStopToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 任务停止工具
 */
public class TaskStopTool extends BaseTool<TaskStopToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(TaskStopTool.class);
    private final BackgroundTaskManager taskManager;

    public TaskStopTool(BackgroundTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String getName() {
        return "task_stop";
    }

    @Override
    public String getDescription() {
        return "停止运行中的后台任务";
    }

    @Override
    public Class<TaskStopToolInput> getInputClass() {
        return TaskStopToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(TaskStopToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                boolean stopped = taskManager.stopTask(input.getTaskId());
                if (stopped) {
                    return ToolResult.success("任务已停止: " + input.getTaskId());
                } else {
                    return ToolResult.error("任务无法停止（可能已完成或不存在）: " + input.getTaskId());
                }
            } catch (Exception e) {
                logger.error("停止任务失败", e);
                return ToolResult.error("停止任务失败: " + e.getMessage());
            }
        });
    }
}
