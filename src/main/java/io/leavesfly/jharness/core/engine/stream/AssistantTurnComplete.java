package io.leavesfly.jharness.core.engine.stream;

/**
 * 助手回合完成事件
 *
 * 表示助手的一轮回复完成。
 */
public class AssistantTurnComplete extends StreamEvent {
    private final String message;

    public AssistantTurnComplete(String message) {
        this.message = message;
    }

    @Override
    public String getEventType() {
        return "assistant_turn_complete";
    }

    public String getMessage() {
        return message;
    }
}
