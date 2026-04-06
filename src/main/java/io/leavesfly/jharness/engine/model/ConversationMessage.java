package io.leavesfly.jharness.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话消息
 *
 * 表示对话中的一条完整消息，包含角色和内容块列表。
 * 用于维护 Agent 与用户之间的对话历史。
 */
public class ConversationMessage {
    private final MessageRole role;
    private final List<ContentBlock> content;

    /**
     * 构造对话消息
     *
     * @param role    消息角色
     * @param content 内容块列表
     */
    @JsonCreator
    public ConversationMessage(
            @JsonProperty("role") MessageRole role,
            @JsonProperty("content") List<ContentBlock> content) {
        this.role = role;
        this.content = content != null ? new ArrayList<>(content) : new ArrayList<>();
    }

    /**
     * 创建用户消息（便捷方法）
     *
     * @param text 文本内容
     * @return 用户消息
     */
    public static ConversationMessage userText(String text) {
        return new ConversationMessage(MessageRole.USER, List.of(new TextBlock(text)));
    }

    /**
     * 创建助手消息（便捷方法）
     *
     * @param text 文本内容
     * @return 助手消息
     */
    public static ConversationMessage assistantText(String text) {
        return new ConversationMessage(MessageRole.ASSISTANT, List.of(new TextBlock(text)));
    }

    public MessageRole getRole() {
        return role;
    }

    public List<ContentBlock> getContent() {
        return Collections.unmodifiableList(content);
    }

    /**
     * 添加内容块
     *
     * @param block 要添加的内容块
     */
    public void addContent(ContentBlock block) {
        content.add(block);
    }

    /**
     * 获取所有工具使用块
     *
     * @return 工具使用块列表
     */
    public List<ToolUseBlock> getToolUses() {
        List<ToolUseBlock> toolUses = new ArrayList<>();
        for (ContentBlock block : content) {
            if (block instanceof ToolUseBlock) {
                toolUses.add((ToolUseBlock) block);
            }
        }
        return Collections.unmodifiableList(toolUses);
    }

    /**
     * 检查是否包含工具使用
     *
     * @return 如果包含工具使用则返回 true
     */
    public boolean hasToolUses() {
        return !getToolUses().isEmpty();
    }

    @Override
    public String toString() {
        return "ConversationMessage{role=" + role + ", content=" + content + "}";
    }
}
