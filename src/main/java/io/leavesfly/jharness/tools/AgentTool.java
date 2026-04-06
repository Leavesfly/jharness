package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tasks.BackgroundTaskManager;
import io.leavesfly.jharness.tasks.TaskRecord;
import io.leavesfly.jharness.tools.input.AgentToolInput;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 工具 - 生成子进程 Agent
 */
public class AgentTool extends BaseTool<AgentToolInput> {
    private final BackgroundTaskManager taskManager;

    public AgentTool(BackgroundTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String getName() {
        return "agent_spawn";
    }

    @Override
    public String getDescription() {
        return "Spawn a sub-process agent to handle complex tasks independently";
    }

    @Override
    public Class<AgentToolInput> getInputClass() {
        return AgentToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(AgentToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (input.getPrompt() == null || input.getPrompt().trim().isEmpty()) {
                    return ToolResult.error("prompt is required");
                }

                Path cwd = context != null ? context.getCwd() : Path.of(System.getProperty("user.dir"));

                TaskRecord task = taskManager.createAgentTask(
                        input.getPrompt(),
                        input.getDescription() != null ? input.getDescription() : "Agent task",
                        cwd,
                        input.getModel(),
                        input.getApiKey()
                );

                String mode = input.getMode() != null ? input.getMode() : "local_agent";
                String msg = String.format(
                        "Agent spawned successfully\n" +
                        "Task ID: %s\n" +
                        "Mode: %s\n" +
                        "Model: %s\n" +
                        "Description: %s\n\n" +
                        "Use /task_get %s to check status\n" +
                        "Use /send_message %s <message> to communicate",
                        task.getId(),
                        mode,
                        input.getModel() != null ? input.getModel() : "default",
                        task.getDescription(),
                        task.getId(),
                        task.getId());

                return ToolResult.success(msg);
            } catch (Exception e) {
                return ToolResult.error("Failed to spawn agent: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(AgentToolInput input) {
        return false;
    }
}
