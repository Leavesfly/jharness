package io.leavesfly.jharness.tools.input;

/**
 * 任务列表工具输入
 */
public class TaskListToolInput {
    /**
     * 可选的状态过滤: RUNNING, COMPLETED, FAILED, STOPPED
     */
    public String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
