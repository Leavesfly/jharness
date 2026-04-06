package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tasks.BackgroundTaskManager;
import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.input.TaskListToolInput;
import io.leavesfly.jharness.tasks.TaskRecord;
import io.leavesfly.jharness.tasks.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 任务列表工具
 */
public class TaskListTool extends BaseTool<TaskListToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(TaskListTool.class);
    private final BackgroundTaskManager taskManager;

    public TaskListTool(BackgroundTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String getName() {
        return "task_list";
    }

    @Override
    public String getDescription() {
        return "列出所有后台任务，支持按状态过滤";
    }

    @Override
    public Class<TaskListToolInput> getInputClass() {
        return TaskListToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(TaskListToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                TaskStatus status = null;
                if (input.getStatus() != null && !input.getStatus().isEmpty()) {
                    try {
                        status = TaskStatus.valueOf(input.getStatus().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error("无效的状态: " + input.getStatus() + "。有效值: RUNNING, COMPLETED, FAILED, STOPPED");
                    }
                }

                List<TaskRecord> tasks = taskManager.listTasks(status);
                if (tasks.isEmpty()) {
                    return ToolResult.success("无后台任务");
                }

                StringBuilder sb = new StringBuilder();
                sb.append("后台任务列表 (共 ").append(tasks.size()).append(" 个):\n\n");
                sb.append(String.format("%-10s %-30s %-12s %s\n", "ID", "描述", "状态", "命令"));
                sb.append("-".repeat(100)).append("\n");

                for (TaskRecord task : tasks) {
                    String desc = task.getDescription().length() > 28 ?
                        task.getDescription().substring(0, 28) + ".." : task.getDescription();
                    String cmd = task.getCommand().length() > 30 ?
                        task.getCommand().substring(0, 30) + ".." : task.getCommand();
                    sb.append(String.format("%-10s %-30s %-12s %s\n",
                        task.getId(), desc, task.getStatus(), cmd));
                }

                return ToolResult.success(sb.toString().trim());
            } catch (Exception e) {
                logger.error("列出任务失败", e);
                return ToolResult.error("列出任务失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(TaskListToolInput input) {
        return true;
    }
}
