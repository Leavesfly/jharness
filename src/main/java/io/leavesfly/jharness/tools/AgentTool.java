package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.agent.tasks.BackgroundTaskManager;
import io.leavesfly.jharness.agent.tasks.TaskRecord;
import io.leavesfly.jharness.core.engine.QueryEngine;
import io.leavesfly.jharness.core.engine.model.ConversationMessage;
import io.leavesfly.jharness.core.engine.model.TextBlock;
import io.leavesfly.jharness.core.engine.stream.AssistantTextDelta;
import io.leavesfly.jharness.core.engine.stream.AssistantTurnComplete;
import io.leavesfly.jharness.core.engine.stream.StreamEvent;
import io.leavesfly.jharness.integration.api.LlmProvider;
import io.leavesfly.jharness.session.permissions.PermissionChecker;
import io.leavesfly.jharness.tools.input.AgentToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 工具（F-P1-1 升级）。
 *
 * 支持两种模式：
 * - {@code in_process}（默认）：在当前 JVM 内创建子 QueryEngine 实例执行 prompt，
 *   共享 API 客户端和工具集，零额外进程开销，适合大多数 sub-agent 场景；
 * - {@code local_agent}：通过子进程启动独立的 JHarness 实例（旧行为），
 *   用于需要隔离上下文/权限的场景。
 */
public class AgentTool extends BaseTool<AgentToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(AgentTool.class);

    private final BackgroundTaskManager taskManager;
    /** 用于 in_process 模式，共享父级 LLM 客户端和工具集。 */
    private LlmProvider sharedProvider;
    private ToolRegistry sharedToolRegistry;
    private PermissionChecker sharedPermissionChecker;

    public AgentTool(BackgroundTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    /**
     * 注入 in_process 模式所需的共享资源（由 JHarnessApplication 在组装时调用）。
     */
    public void configureInProcess(LlmProvider provider, ToolRegistry toolRegistry,
                                   PermissionChecker permissionChecker) {
        this.sharedProvider = provider;
        this.sharedToolRegistry = toolRegistry;
        this.sharedPermissionChecker = permissionChecker;
    }

    @Override
    public String getName() {
        return "agent_spawn";
    }

    @Override
    public String getDescription() {
        return "Spawn a sub-agent to handle complex tasks. mode=in_process (default, same JVM) or local_agent (subprocess).";
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

                String mode = input.getMode() != null ? input.getMode() : "in_process";
                Path cwd = context != null ? context.getCwd() : Path.of(System.getProperty("user.dir"));

                if ("in_process".equals(mode)) {
                    return runInProcess(input, cwd);
                } else {
                    return runAsSubProcess(input, cwd);
                }
            } catch (Exception e) {
                logger.error("Agent 任务失败", e);
                return ToolResult.error("Failed to spawn agent: " + e.getMessage());
            }
        });
    }

    /**
     * 进程内模式（F-P1-1）：创建子 QueryEngine 实例同步执行 prompt 并收集结果。
     */
    private ToolResult runInProcess(AgentToolInput input, Path cwd) {
        if (sharedProvider == null || sharedToolRegistry == null) {
            return ToolResult.error("in_process 模式未配置共享资源，请使用 mode=local_agent 或检查初始化");
        }

        // 创建一个独立的 QueryEngine 实例（不共享消息历史，但共享 API 客户端）
        String subSystemPrompt = "你是一个 sub-agent，专注执行以下任务。完成后给出简洁的结果总结。";
        QueryEngine subEngine = new QueryEngine(
                sharedProvider, sharedToolRegistry, sharedPermissionChecker,
                cwd, subSystemPrompt, 10);

        StringBuilder resultText = new StringBuilder();
        try {
            subEngine.submitMessage(input.getPrompt(), event -> {
                if (event instanceof AssistantTextDelta delta) {
                    resultText.append(delta.getText());
                }
            }).join();
        } catch (Exception e) {
            logger.error("in_process sub-agent 执行失败", e);
            return ToolResult.error("Sub-agent 执行失败: " + e.getMessage());
        }

        String output = resultText.toString().trim();
        if (output.isEmpty()) {
            output = "(sub-agent 未返回文本输出)";
        }
        // 截断过长输出
        if (output.length() > 10000) {
            output = output.substring(0, 10000) + "\n...(输出已截断)";
        }
        return ToolResult.success(output);
    }

    /**
     * 子进程模式（旧行为）：通过 BackgroundTaskManager 启动独立 JVM。
     */
    private ToolResult runAsSubProcess(AgentToolInput input, Path cwd) {
        TaskRecord task = taskManager.createAgentTask(
                input.getPrompt(),
                input.getDescription() != null ? input.getDescription() : "Agent task",
                cwd,
                input.getModel(),
                input.getApiKey()
        );

        return ToolResult.success(String.format(
                "Agent spawned (subprocess mode)\nTask ID: %s\nDescription: %s\n\n"
                        + "Use /task_get %s to check status",
                task.getId(), task.getDescription(), task.getId()));
    }

    @Override
    public boolean isReadOnly(AgentToolInput input) {
        return false;
    }
}
