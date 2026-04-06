package io.leavesfly.jharness.tools.input;

/**
 * RemoteTriggerTool 的输入模型
 * 
 * 用于按名称触发执行已注册的定时作业。
 */
public class RemoteTriggerToolInput {
    private String name;
    private Integer timeoutSeconds;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds != null ? timeoutSeconds : 120;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
