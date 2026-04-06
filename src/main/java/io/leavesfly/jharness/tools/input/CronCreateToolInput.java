package io.leavesfly.jharness.tools.input;

/**
 * CronCreateTool 的输入模型
 * 
 * 用于创建或更新定时作业定义。
 */
public class CronCreateToolInput {
    private String name;
    private String schedule;
    private String command;
    private String cwd;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSchedule() {
        return schedule;
    }

    public void setSchedule(String schedule) {
        this.schedule = schedule;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }
}
