package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * 任务查询工具输入
 */
public class TaskGetToolInput {
    @NotBlank
    public String taskId;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
}
