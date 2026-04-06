package io.leavesfly.jharness.tools.input;

/**
 * 任务更新工具输入
 */
public class TaskUpdateToolInput {
    public String taskId;
    public String description;
    public String statusNote;
    public Integer progress;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatusNote() { return statusNote; }
    public void setStatusNote(String statusNote) { this.statusNote = statusNote; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
}
