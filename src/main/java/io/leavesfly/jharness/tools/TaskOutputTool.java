package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tasks.BackgroundTaskManager;
import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.input.TaskOutputToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 任务输出读取工具
 */
public class TaskOutputTool extends BaseTool<TaskOutputToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(TaskOutputTool.class);
    private final BackgroundTaskManager taskManager;

    public TaskOutputTool(BackgroundTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String getName() {
        return "task_output";
    }

    @Override
    public String getDescription() {
        return "读取后台任务的输出日志";
    }

    @Override
    public Class<TaskOutputToolInput> getInputClass() {
        return TaskOutputToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(TaskOutputToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String output = taskManager.readTaskOutput(input.getTaskId());
                if (output == null || output.isEmpty()) {
                    return ToolResult.success("(任务暂无输出)");
                }

                // 限制输出长度
                int maxBytes = input.getMaxBytes() != null ? input.getMaxBytes() : 12000;
                if (output.length() > maxBytes) {
                    output = output.substring(0, maxBytes) + "\n...(输出已截断，共 " + output.length() + " 字符)";
                }

                return ToolResult.success(output);
            } catch (Exception e) {
                logger.error("读取任务输出失败", e);
                return ToolResult.error("读取任务输出失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(TaskOutputToolInput input) {
        return true;
    }
}
