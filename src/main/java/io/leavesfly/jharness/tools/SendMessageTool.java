package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tasks.BackgroundTaskManager;
import io.leavesfly.jharness.tasks.TaskRecord;
import io.leavesfly.jharness.tasks.TaskStatus;
import io.leavesfly.jharness.tools.input.SendMessageToolInput;

import java.util.concurrent.CompletableFuture;

/**
 * 发送消息工具 - 向运行中的 Agent 任务发送消息
 */
public class SendMessageTool extends BaseTool<SendMessageToolInput> {
    private final BackgroundTaskManager taskManager;

    public SendMessageTool(BackgroundTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String getName() {
        return "send_message";
    }

    @Override
    public String getDescription() {
        return "Send a message to a running agent task's stdin";
    }

    @Override
    public Class<SendMessageToolInput> getInputClass() {
        return SendMessageToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(SendMessageToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (input.getTaskId() == null || input.getTaskId().trim().isEmpty()) {
                    return ToolResult.error("taskId is required");
                }
                if (input.getMessage() == null || input.getMessage().trim().isEmpty()) {
                    return ToolResult.error("message is required");
                }

                TaskRecord task = taskManager.getTask(input.getTaskId());
                if (task == null) {
                    return ToolResult.error("Task not found: " + input.getTaskId());
                }

                if (task.getStatus() != TaskStatus.RUNNING) {
                    return ToolResult.error("Task is not running (status: " + task.getStatus() + ")");
                }

                boolean success = taskManager.writeToTask(input.getTaskId(), input.getMessage());
                if (success) {
                    return ToolResult.success("Message sent to task " + input.getTaskId());
                } else {
                    return ToolResult.error("Failed to send message to task " + input.getTaskId() +
                            " - task may not be accepting input");
                }
            } catch (Exception e) {
                return ToolResult.error("Failed to send message: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(SendMessageToolInput input) {
        return false;
    }
}
