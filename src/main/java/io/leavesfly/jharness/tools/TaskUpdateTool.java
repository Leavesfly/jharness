package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.agent.tasks.BackgroundTaskManager;
import io.leavesfly.jharness.tools.input.TaskUpdateToolInput;
import io.leavesfly.jharness.agent.tasks.TaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 任务更新工具
 */
public class TaskUpdateTool extends BaseTool<TaskUpdateToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(TaskUpdateTool.class);
    private final BackgroundTaskManager taskManager;

    public TaskUpdateTool(BackgroundTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String getName() {
        return "task_update";
    }

    @Override
    public String getDescription() {
        return "更新后台任务的元数据（描述、状态备注）";
    }

    @Override
    public Class<TaskUpdateToolInput> getInputClass() {
        return TaskUpdateToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(TaskUpdateToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                TaskRecord task = taskManager.getTask(input.getTaskId());
                if (task == null) {
                    return ToolResult.error("任务不存在: " + input.getTaskId());
                }

                StringBuilder sb = new StringBuilder();
                sb.append("任务 ").append(input.getTaskId()).append(" 已更新:\n");

                if (input.getDescription() != null && !input.getDescription().isEmpty()) {
                    task.setDescription(input.getDescription());
                    sb.append("  描述: ").append(input.getDescription()).append("\n");
                }

                if (input.getStatusNote() != null && !input.getStatusNote().isEmpty()) {
                    task.addMetadata("status_note", input.getStatusNote());
                    sb.append("  状态备注: ").append(input.getStatusNote()).append("\n");
                }

                if (input.getProgress() != null) {
                    task.addMetadata("progress", input.getProgress().toString());
                    sb.append("  进度: ").append(input.getProgress()).append("%\n");
                }

                return ToolResult.success(sb.toString().trim());
            } catch (Exception e) {
                logger.error("更新任务失败", e);
                return ToolResult.error("更新任务失败: " + e.getMessage());
            }
        });
    }
}
