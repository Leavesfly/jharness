package io.leavesfly.jharness.kernel.engine.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.leavesfly.jharness.kernel.engine.model.ToolResultBlock;
import io.leavesfly.jharness.kernel.engine.model.ToolUseBlock;
import io.leavesfly.jharness.kernel.engine.stream.StreamEvent;
import io.leavesfly.jharness.kernel.engine.stream.ToolExecutionCompleted;
import io.leavesfly.jharness.kernel.plan.ExecutionPlan;
import io.leavesfly.jharness.kernel.plan.PlanStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plan Mode 拦截器：当处于 Plan Mode 时，把 LLM 提出的工具调用记录为
 * {@link PlanStep} 而不是真正执行，并返回友好的 {@link ToolResultBlock}
 * 让 LLM 知道"已记录到执行计划"，以便继续输出后续步骤。
 */
public final class PlanModeInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(PlanModeInterceptor.class);

    private PlanModeInterceptor() {}

    public static List<ToolResultBlock> intercept(List<ToolUseBlock> toolUses,
                                                   ExecutionPlan plan,
                                                   Consumer<StreamEvent> eventConsumer) {
        List<ToolResultBlock> results = new ArrayList<>();
        for (ToolUseBlock toolUse : toolUses) {
            String description = buildDescription(toolUse);
            String details = toolUse.getInput() != null ? toolUse.getInput().toString() : "";
            PlanStep step = new PlanStep(plan.size(), toolUse.getName(), description, details);
            plan.addStep(step);

            String msg = String.format("📋 已记录到执行计划 (步骤 %d): [%s] %s",
                    step.getIndex() + 1, toolUse.getName(), description);
            eventConsumer.accept(new ToolExecutionCompleted(toolUse.getName(), toolUse.getId(), msg, false));

            results.add(new ToolResultBlock(toolUse.getId(), msg, false));
        }
        logger.info("Plan Mode: 已拦截 {} 个工具调用，计划共 {} 步", toolUses.size(), plan.size());
        return results;
    }

    /** 从工具调用 input 中抽出可读描述（file_path / command / path 三选一）。 */
    private static String buildDescription(ToolUseBlock toolUse) {
        JsonNode input = toolUse.getInput();
        if (input == null) return toolUse.getName();
        StringBuilder desc = new StringBuilder();
        if (input.has("file_path")) {
            desc.append(input.get("file_path").asText());
        } else if (input.has("command")) {
            String cmd = input.get("command").asText();
            desc.append(cmd.length() > 60 ? cmd.substring(0, 60) + "..." : cmd);
        } else if (input.has("path")) {
            desc.append(input.get("path").asText());
        }
        return desc.length() > 0 ? desc.toString() : toolUse.getName();
    }
}
