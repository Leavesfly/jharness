package io.leavesfly.jharness.engine.stream;

/**
 * 工具执行开始事件
 */
public class ToolExecutionStarted extends StreamEvent {
    private final String toolName;
    private final String toolId;

    public ToolExecutionStarted(String toolName, String toolId) {
        this.toolName = toolName;
        this.toolId = toolId;
    }

    @Override
    public String getEventType() {
        return "tool_execution_started";
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolId() {
        return toolId;
    }
}
