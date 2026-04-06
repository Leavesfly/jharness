package io.leavesfly.jharness.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 工具使用内容块
 *
 * 表示助手请求使用工具的内容块，包含工具名称、输入参数和唯一标识。
 */
public class ToolUseBlock extends ContentBlock {
    private final String id;
    private final String name;
    private final JsonNode input;

    /**
     * 构造工具使用块
     *
     * @param id    工具调用唯一标识
     * @param name  工具名称
     * @param input 工具输入参数（JSON 结构）
     */
    @JsonCreator
    public ToolUseBlock(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("input") JsonNode input) {
        this.id = id;
        this.name = name;
        this.input = input;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper SHARED_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 从 Map 构造工具使用块（便捷方法）
     *
     * @param id    工具调用唯一标识
     * @param name  工具名称
     * @param input 工具输入参数（Map 结构）
     */
    public ToolUseBlock(String id, String name, Map<String, Object> input) {
        java.util.Objects.requireNonNull(id, "Tool use id cannot be null");
        java.util.Objects.requireNonNull(name, "Tool name cannot be null");
        this.id = id;
        this.name = name;
        this.input = SHARED_MAPPER.valueToTree(input);
    }

    @Override
    public String getType() {
        return "tool_use";
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public JsonNode getInput() {
        return input;
    }

    @Override
    public String toString() {
        return "ToolUseBlock{id='" + id + "', name='" + name + "', input=" + input + "}";
    }
}
