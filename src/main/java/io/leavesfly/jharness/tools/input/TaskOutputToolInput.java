package io.leavesfly.jharness.tools.input;

/**
 * 任务输出工具输入
 */
public class TaskOutputToolInput {
    public String taskId;
    public Integer maxBytes;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Integer getMaxBytes() { return maxBytes; }
    public void setMaxBytes(Integer maxBytes) { this.maxBytes = maxBytes; }
}
