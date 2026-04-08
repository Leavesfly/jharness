package io.leavesfly.jharness.core.engine.stream;

/**
 * 助手文本增量事件
 *
 * 表示助手输出的文本增量，用于流式显示助手回复。
 */
public class AssistantTextDelta extends StreamEvent {
    private final String text;

    /**
     * 构造助手文本增量事件
     *
     * @param text 文本增量内容
     */
    public AssistantTextDelta(String text) {
        this.text = text;
    }

    @Override
    public String getEventType() {
        return "assistant_text_delta";
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return "AssistantTextDelta{text='" + text + "'}";
    }
}
