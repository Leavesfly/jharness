package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * 任务创建工具输入
 */
public class TaskCreateToolInput {
    @NotBlank
    public String command;

    public String description;

    public String cwd;

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }
}
