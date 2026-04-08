package io.leavesfly.jharness.agent.hooks;

/**
 * 钩子事件类型枚举
 */
public enum HookEvent {
    SESSION_START("session_start"),
    SESSION_END("session_end"),
    PRE_TOOL_USE("pre_tool_use"),
    POST_TOOL_USE("post_tool_use");

    private final String value;

    HookEvent(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
