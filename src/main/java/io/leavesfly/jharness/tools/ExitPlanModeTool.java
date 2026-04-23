package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.tools.input.ExitPlanModeToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 退出计划模式工具
 */
public class ExitPlanModeTool extends BaseTool<ExitPlanModeToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(ExitPlanModeTool.class);
    private final Settings settings;

    public ExitPlanModeTool(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String getName() {
        return "exit_plan_mode";
    }

    @Override
    public String getDescription() {
        return "退出计划模式，恢复到默认权限模式";
    }

    @Override
    public Class<ExitPlanModeToolInput> getInputClass() {
        return ExitPlanModeToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(ExitPlanModeToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // F-P1-2：退出时检查是否有未执行的计划
                io.leavesfly.jharness.core.plan.ExecutionPlan plan = EnterPlanModeTool.getActivePlan();
                String planSummary = "";
                if (plan != null && plan.hasPendingWork()) {
                    planSummary = "\n⚠ 注意：有未执行的计划步骤被丢弃：\n" + plan.toSummary();
                }
                EnterPlanModeTool.clearPlan();
                settings.setPermissionMode("default");
                return ToolResult.success("已退出计划模式，恢复到默认模式。" + planSummary);
            } catch (Exception e) {
                logger.error("切换模式失败", e);
                return ToolResult.error("切换模式失败: " + e.getMessage());
            }
        });
    }
}
