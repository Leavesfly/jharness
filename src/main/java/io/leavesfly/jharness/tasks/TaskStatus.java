package io.leavesfly.jharness.tasks;

/**
 * 任务状态枚举
 */
public enum TaskStatus {
    PENDING,    // 等待启动
    RUNNING,    // 运行中
    COMPLETED,  // 已完成
    FAILED,     // 失败
    STOPPED,    // 已停止
    KILLED      // 被强制终止
}
