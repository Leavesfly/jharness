package io.leavesfly.jharness.engine.stream;

/**
 * 工具执行完成事件
 */
public class ToolExecutionCompleted extends StreamEvent {
    private final String toolName;
    private final String toolId;
    private final String result;
    private final boolean isError;

    public ToolExecutionCompleted(String toolName, String toolId, String result, boolean isError) {
        this.toolName = toolName;
        this.toolId = toolId;
        this.result = result;
        this.isError = isError;
    }

    @Override
    public String getEventType() {
        return "tool_execution_completed";
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolId() {
        return toolId;
    }

    public String getResult() {
        return result;
    }

    public boolean isError() {
        return isError;
    }
}
