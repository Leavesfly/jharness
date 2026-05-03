package io.leavesfly.jharness.agent.hooks;

/**
 * 钩子事件类型枚举。
 *
 * 【P2】对齐 Claude Code plugin 生态，补齐 USER_PROMPT_SUBMIT / STOP / NOTIFICATION /
 * SUBAGENT_STOP 四类新事件。现有订阅方在 switch 未命中时会走默认分支，向后兼容。
 */
public enum HookEvent {
    SESSION_START("session_start"),
    SESSION_END("session_end"),
    PRE_TOOL_USE("pre_tool_use"),
    POST_TOOL_USE("post_tool_use"),
    /** 用户输入提交到 QueryEngine 之前触发，可用于提示词审计 / 重写 / 拦截。 */
    USER_PROMPT_SUBMIT("user_prompt_submit"),
    /** 一次完整 ReAct 循环结束（无论是否达到 maxTurns）触发。 */
    STOP("stop"),
    /** 子代理（AgentOrchestrator）执行结束触发。 */
    SUBAGENT_STOP("subagent_stop"),
    /** 通用通知事件（如预算告警、MCP 重连提示等），不阻塞主流程。 */
    NOTIFICATION("notification");

    private final String value;

    HookEvent(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
