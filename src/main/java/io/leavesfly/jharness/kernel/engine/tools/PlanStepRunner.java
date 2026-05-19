package io.leavesfly.jharness.kernel.engine.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.kernel.engine.model.ToolUseBlock;
import io.leavesfly.jharness.kernel.engine.stream.StreamEvent;
import io.leavesfly.jharness.kernel.engine.stream.ToolExecutionCompleted;
import io.leavesfly.jharness.kernel.engine.stream.ToolExecutionStarted;
import io.leavesfly.jharness.kernel.plan.ExecutionPlan;
import io.leavesfly.jharness.kernel.plan.PlanStep;
import io.leavesfly.jharness.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * 已批准计划步骤的执行器。
 *
 * 由 /approve 或 /approve_all 命令触发：按 {@link PlanStep#getIndex()} 顺序逐个执行，
 * 每步通过 {@link ToolCallDispatcher#executeToolCall(ToolUseBlock)} 走与正常工具调用相同的
 * 权限/执行通路；执行结果回填到 step 并通过事件向 UI 推送进度。
 */
public final class PlanStepRunner {

    private static final Logger logger = LoggerFactory.getLogger(PlanStepRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallDispatcher dispatcher;

    public PlanStepRunner(ToolCallDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public String run(ExecutionPlan plan, Consumer<StreamEvent> eventConsumer) {
        List<PlanStep> approvedSteps = plan.getApprovedSteps();
        if (approvedSteps.isEmpty()) {
            return "没有待执行的已批准步骤。";
        }

        int successCount = 0;
        int failCount = 0;

        for (PlanStep step : approvedSteps) {
            logger.info("执行计划步骤 {}: [{}] {}",
                    step.getIndex() + 1, step.getToolName(), step.getDescription());
            String virtualId = "plan_step_" + step.getIndex();
            eventConsumer.accept(new ToolExecutionStarted(step.getToolName(), virtualId));

            try {
                JsonNode inputNode = MAPPER.readTree(step.getDetails());
                ToolUseBlock syntheticToolUse = new ToolUseBlock(virtualId, step.getToolName(), inputNode);
                ToolResult result = dispatcher.executeToolCall(syntheticToolUse).join();

                step.setResult(result.getOutput());
                if (result.isError()) {
                    step.setStatus(PlanStep.StepStatus.FAILED);
                    failCount++;
                } else {
                    step.setStatus(PlanStep.StepStatus.EXECUTED);
                    successCount++;
                }
                eventConsumer.accept(new ToolExecutionCompleted(
                        step.getToolName(), virtualId, result.getOutput(), result.isError()));
            } catch (Exception e) {
                logger.error("执行计划步骤 {} 失败", step.getIndex() + 1, e);
                step.setStatus(PlanStep.StepStatus.FAILED);
                step.setResult("执行失败: " + e.getMessage());
                failCount++;
                eventConsumer.accept(new ToolExecutionCompleted(
                        step.getToolName(), virtualId, "执行失败: " + e.getMessage(), true));
            }
        }

        return String.format("计划执行完成：%d 成功，%d 失败（共 %d 步）",
                successCount, failCount, approvedSteps.size());
    }
}
