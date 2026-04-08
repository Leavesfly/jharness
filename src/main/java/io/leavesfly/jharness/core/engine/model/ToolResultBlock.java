package io.leavesfly.jharness.core.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 工具结果内容块
 *
 * 表示工具执行结果的内容块，包含工具调用 ID、结果内容和错误标志。
 */
public class ToolResultBlock extends ContentBlock {
    private final String toolUseId;
    private final String content;
    private final boolean isError;

    /**
     * 构造工具结果块
     *
     * @param toolUseId 关联的工具调用 ID
     * @param content   工具执行结果内容
     * @param isError   是否为错误结果
     */
    @JsonCreator
    public ToolResultBlock(
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("content") String content,
            @JsonProperty("is_error") boolean isError) {
        this.toolUseId = toolUseId;
        this.content = content;
        this.isError = isError;
    }

    /**
     * 创建成功结果（便捷方法）
     *
     * @param toolUseId 关联的工具调用 ID
     * @param content   成功结果内容
     * @return 工具结果块
     */
    public static ToolResultBlock success(String toolUseId, String content) {
        return new ToolResultBlock(toolUseId, content, false);
    }

    /**
     * 创建错误结果（便捷方法）
     *
     * @param toolUseId 关联的工具调用 ID
     * @param error     错误信息
     * @return 工具结果块
     */
    public static ToolResultBlock error(String toolUseId, String error) {
        return new ToolResultBlock(toolUseId, error, true);
    }

    @Override
    public String getType() {
        return "tool_result";
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public String getContent() {
        return content;
    }

    public boolean isError() {
        return isError;
    }

    @Override
    public String toString() {
        return "ToolResultBlock{toolUseId='" + toolUseId + "', content='" + content + "', isError=" + isError + "}";
    }
}
