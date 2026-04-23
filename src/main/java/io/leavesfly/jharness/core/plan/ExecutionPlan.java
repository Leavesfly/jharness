package io.leavesfly.jharness.core.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 执行计划（F-P1-2）。
 *
 * 在 Plan Mode 下，LLM 输出的所有工具调用会被拦截并转化为 PlanStep 列表存入此对象；
 * 用户可以通过 /plan 查看、/approve 确认、/approve_all 全部确认后才真正执行。
 *
 * 生命周期：
 * 1. Plan Mode 开启 → 创建空 ExecutionPlan；
 * 2. 每次 LLM 回复包含 tool_use 时，引擎不执行而是追加 PlanStep（PENDING）；
 * 3. 用户 /approve [n] → 将第 n 步标记为 APPROVED；
 * 4. 用户 /approve_all → 所有 PENDING 标记为 APPROVED；
 * 5. 引擎按序执行所有 APPROVED 步骤，标记为 EXECUTED/FAILED；
 * 6. Plan Mode 退出 → plan 被清空。
 */
public class ExecutionPlan {

    private final List<PlanStep> steps = new ArrayList<>();
    private final Instant createdAt = Instant.now();

    public void addStep(PlanStep step) {
        steps.add(step);
    }

    public List<PlanStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public int size() {
        return steps.size();
    }

    public PlanStep getStep(int index) {
        if (index < 0 || index >= steps.size()) return null;
        return steps.get(index);
    }

    /** 将指定步骤标记为 APPROVED；返回是否成功。 */
    public boolean approve(int index) {
        PlanStep step = getStep(index);
        if (step == null || step.getStatus() != PlanStep.StepStatus.PENDING) return false;
        step.setStatus(PlanStep.StepStatus.APPROVED);
        return true;
    }

    /** 将所有 PENDING 步骤标记为 APPROVED。 */
    public int approveAll() {
        int count = 0;
        for (PlanStep step : steps) {
            if (step.getStatus() == PlanStep.StepStatus.PENDING) {
                step.setStatus(PlanStep.StepStatus.APPROVED);
                count++;
            }
        }
        return count;
    }

    /** 跳过指定步骤。 */
    public boolean skip(int index) {
        PlanStep step = getStep(index);
        if (step == null || step.getStatus() != PlanStep.StepStatus.PENDING) return false;
        step.setStatus(PlanStep.StepStatus.SKIPPED);
        return true;
    }

    /** 获取所有已批准但未执行的步骤。 */
    public List<PlanStep> getApprovedSteps() {
        return steps.stream()
                .filter(s -> s.getStatus() == PlanStep.StepStatus.APPROVED)
                .toList();
    }

    /** 是否有待处理（PENDING 或 APPROVED）的步骤。 */
    public boolean hasPendingWork() {
        return steps.stream().anyMatch(s ->
                s.getStatus() == PlanStep.StepStatus.PENDING
                        || s.getStatus() == PlanStep.StepStatus.APPROVED);
    }

    /** 生成可读的计划摘要。 */
    public String toSummary() {
        if (steps.isEmpty()) return "(计划为空)";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("执行计划 (%d 步):\n", steps.size()));
        for (PlanStep step : steps) {
            String icon = switch (step.getStatus()) {
                case PENDING -> "⏳";
                case APPROVED -> "✅";
                case EXECUTED -> "✔️";
                case SKIPPED -> "⏭️";
                case FAILED -> "❌";
            };
            sb.append(String.format("  %s %d. [%s] %s\n",
                    icon, step.getIndex() + 1, step.getToolName(), step.getDescription()));
        }
        return sb.toString();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 清空所有步骤。 */
    public void clear() {
        steps.clear();
    }
}
