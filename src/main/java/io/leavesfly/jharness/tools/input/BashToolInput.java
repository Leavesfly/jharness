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
}
