package io.leavesfly.jharness.agent.hooks;

/**
 * 钩子事件类型枚举。
 *
 * <p>【P2】对齐 Claude Code plugin 生态，补齐 USER_PROMPT_SUBMIT / STOP / NOTIFICATION /
 * SUBAGENT_STOP 四类新事件。
 *
 * <h3>当前发射位置（对齐代码实现）</h3>
 * <ul>
 *   <li>{@link #SESSION_START}：{@code JHarnessApplication.buildQueryEngine()} 组装完毕后；</li>
 *   <li>{@link #SESSION_END}：{@code JHarnessApplication} 进程退出前（配合 engine.close）；</li>
 *   <li>{@link #USER_PROMPT_SUBMIT}：{@code QueryEngine.submitMessage()} 入口（消息入队前）；</li>
 *   <li>{@link #STOP}：{@code QueryEngine.submitMessage()} 正常返回 / 取消 / 超时后；</li>
 *   <li>{@link #SUBAGENT_STOP}：{@code AgentOrchestrator.executeSingle()} 结束后；</li>
 *   <li>{@link #PRE_TOOL_USE} / {@link #POST_TOOL_USE} / {@link #NOTIFICATION}：
 *       <b>当前保留但未发射</b>，待工具执行管线与通知中心落地后再接入；
 *       插件中声明这些事件不会报错，只会被静默跳过。</li>
 * </ul>
 *
 * <p>发射方调用约定：payload 至少包含 {@code "session_id"} / {@code "model"} / {@code "cwd"}；
 * 各事件可再扩展额外字段（如 USER_PROMPT_SUBMIT 会带 {@code "prompt"}，STOP 会带 {@code "turns"}）。
 */
public enum HookEvent {
    /** 会话开始时触发。发射点：{@code JHarnessApplication.buildQueryEngine}。 */
    SESSION_START("session_start"),
    /** 会话结束时触发。发射点：{@code JHarnessApplication.runInteractiveMode/runPrintMode} close 前。 */
    SESSION_END("session_end"),
    /** 工具调用前触发。<b>当前保留未发射</b>，待工具执行管线接入。 */
    PRE_TOOL_USE("pre_tool_use"),
    /** 工具调用后触发。<b>当前保留未发射</b>，待工具执行管线接入。 */
    POST_TOOL_USE("post_tool_use"),
    /** 用户输入提交到 QueryEngine 之前触发。发射点：{@code QueryEngine.submitMessage} 入口。 */
    USER_PROMPT_SUBMIT("user_prompt_submit"),
    /** 一次完整 ReAct 循环结束（正常 / 取消 / 超时）触发。发射点：{@code QueryEngine.submitMessage} 返回前。 */
    STOP("stop"),
    /** 子代理（AgentOrchestrator）执行结束触发。发射点：{@code AgentOrchestrator.executeSingle} 返回前。 */
    SUBAGENT_STOP("subagent_stop"),
    /** 通用通知事件（预算告警、MCP 重连等）。<b>当前保留未发射</b>，待通知中心落地。 */
    NOTIFICATION("notification");

    private final String value;

    HookEvent(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
