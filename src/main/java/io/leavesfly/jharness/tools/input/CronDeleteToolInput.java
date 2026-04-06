package io.leavesfly.jharness.tools.input;

/**
 * CronDeleteTool 的输入模型
 * 
 * 用于删除指定的定时作业。
 */
public class CronDeleteToolInput {
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
