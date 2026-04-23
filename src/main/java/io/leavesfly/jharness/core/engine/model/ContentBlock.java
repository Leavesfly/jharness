package io.leavesfly.jharness.core.engine.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 内容块抽象类
 *
 * 消息内容的基类，支持多态序列化。
 * 使用 Jackson 的 @JsonTypeInfo 和 @JsonSubTypes 实现类型区分。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result")
})
public abstract class ContentBlock {
    /**
     * 获取内容块类型
     *
     * @return 类型标识符
     */
    public abstract String getType();
}
