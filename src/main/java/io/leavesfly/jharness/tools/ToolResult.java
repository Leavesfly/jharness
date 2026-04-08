package io.leavesfly.jharness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.leavesfly.jharness.core.engine.model.ToolResultBlock;

/**
 * 工具执行结果
 *
 * 表示工具执行的返回值，包含输出内容、错误标志和元数据。
 */
public class ToolResult {
    private final String output;
    private final boolean isError;
    private final JsonNode metadata;

    public ToolResult(String output, boolean isError) {
        this(output, isError, null);
    }

    public ToolResult(String output, boolean isError, JsonNode metadata) {
        this.output = output;
        this.isError = isError;
        this.metadata = metadata;
    }

    /**
     * 创建成功结果
     */
    public static ToolResult success(String output) {
        return new ToolResult(output, false);
    }

    /**
     * 创建错误结果
     */
    public static ToolResult error(String errorMessage) {
        return new ToolResult(errorMessage, true);
    }

    /**
     * 转换为 ToolResultBlock
     *
     * @param toolUseId 工具调用 ID
     * @return 工具结果块
     */
    public ToolResultBlock toBlock(String toolUseId) {
        return new ToolResultBlock(toolUseId, output, isError);
    }

    public String getOutput() {
        return output;
    }

    public boolean isError() {
        return isError;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "ToolResult{output='" + (output.length() > 100 ? output.substring(0, 100) + "..." : output) +
                "', isError=" + isError + "}";
    }
}
