package io.leavesfly.jharness.core.plan;

/**
 * 执行计划中的单个步骤（F-P1-2）。
 *
 * Plan Mode 下，LLM 不直接修改文件，而是输出结构化的执行计划；
 * 每个 PlanStep 描述一个原子操作（如编辑文件、执行命令、创建文件等），
 * 用户通过 /approve 确认后再交由引擎真正执行。
 */
public class PlanStep {

    public enum StepStatus {
        PENDING,
        APPROVED,
        EXECUTED,
        SKIPPED,
        FAILED
    }

    private final int index;
    private final String toolName;
    private final String description;
    private final String details;
    private StepStatus status;
    private String result;

    public PlanStep(int index, String toolName, String description, String details) {
        this.index = index;
        this.toolName = toolName;
        this.description = description;
        this.details = details;
        this.status = StepStatus.PENDING;
    }

    public int getIndex() { return index; }
    public String getToolName() { return toolName; }
    public String getDescription() { return description; }
    public String getDetails() { return details; }
    public StepStatus getStatus() { return status; }
    public void setStatus(StepStatus status) { this.status = status; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    @Override
    public String toString() {
        return String.format("[%d] %s (%s) - %s: %s",
                index, status, toolName, description,
                details != null && details.length() > 80 ? details.substring(0, 80) + "..." : details);
    }
}
