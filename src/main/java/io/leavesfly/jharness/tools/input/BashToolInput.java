package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Bash 工具输入
 */
public class BashToolInput {
    @NotBlank(message = "command 不能为空")
    private String command;

    @Min(value = 1, message = "timeout 最小为 1")
    @Max(value = 600, message = "timeout 最大为 600 秒")
    private int timeout = 120;

    private String description;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 持久 shell 会话标识（F-P1-5）。
     *
     * 为空时每次命令都使用一次性 ProcessBuilder 进程（与旧行为兼容）；
     * 非空时会把命令发送到对应 {@code sessionId} 的长驻 bash 会话中，
     * 跨命令共享环境变量、工作目录、shell 别名等上下文。
     */
    private String session_id;

    public String getSession_id() {
        return session_id;
    }

    public void setSession_id(String session_id) {
        this.session_id = session_id;
    }
}
