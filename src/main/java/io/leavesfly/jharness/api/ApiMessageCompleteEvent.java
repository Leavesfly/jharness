package io.leavesfly.jharness.api;

import io.leavesfly.jharness.engine.model.ToolUseBlock;
import io.leavesfly.jharness.engine.model.UsageSnapshot;

import java.util.List;

/**
 * API 消息完成事件
 *
 * 表示一次 API 调用的完整响应，包含消息内容、工具调用和使用量。
 */
public class ApiMessageCompleteEvent {
    private final String textContent;
    private final List<ToolUseBlock> toolUses;
    private final UsageSnapshot usage;

    public ApiMessageCompleteEvent(String textContent, List<ToolUseBlock> toolUses, UsageSnapshot usage) {
        this.textContent = textContent;
        this.toolUses = toolUses;
        this.usage = usage;
    }

    public String getTextContent() {
        return textContent;
    }

    public List<ToolUseBlock> getToolUses() {
        return toolUses;
    }

    public UsageSnapshot getUsage() {
        return usage;
    }

    public boolean hasToolUses() {
        return toolUses != null && !toolUses.isEmpty();
    }
}
