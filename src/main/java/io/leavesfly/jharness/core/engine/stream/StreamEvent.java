package io.leavesfly.jharness.core.engine.stream;

/**
 * 流式事件抽象类
 *
 * Agent 查询过程中产生的各类事件的基类。
 * 用于实时向前端推送处理进度和状态。
 */
public abstract class StreamEvent {
    /**
     * 获取事件类型标识
     *
     * @return 事件类型
     */
    public abstract String getEventType();
}
