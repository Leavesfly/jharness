package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tasks.BackgroundTaskManager;
import io.leavesfly.jharness.tasks.TaskRecord;
import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.input.TaskCreateToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * 任务创建工具
 */
public class TaskCreateTool extends BaseTool<TaskCreateToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(TaskCreateTool.class);
    private final BackgroundTaskManager taskManager;

    public TaskCreateTool(BackgroundTaskManager taskManager) { this.taskManager = taskManager; }

    @Override
    public String getName() { return "task_create"; }

    @Override
    public String getDescription() { return "创建并启动一个后台 shell 任务"; }

    @Override
    public Class<TaskCreateToolInput> getInputClass() { return TaskCreateToolInput.class; }

    @Override
    public CompletableFuture<ToolResult> execute(TaskCreateToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String command = input.getCommand();
                String description = input.getDescription() != null ? input.getDescription() : command;
                Path cwd = input.getCwd() != null ? Paths.get(input.getCwd()) : context.getCwd();

                TaskRecord task = taskManager.createShellTask(command, description, cwd);
                return ToolResult.success("任务已创建: " + task.getId() + "\n命令: " + command);
            } catch (Exception e) {
                logger.error("创建任务失败", e);
                return ToolResult.error("创建任务失败: " + e.getMessage());
            }
        });
    }
}
