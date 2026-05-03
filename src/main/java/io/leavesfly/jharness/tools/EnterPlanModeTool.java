package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.core.plan.ExecutionPlan;
import io.leavesfly.jharness.session.permissions.PermissionChecker;
import io.leavesfly.jharness.session.permissions.PermissionMode;
import io.leavesfly.jharness.tools.input.EnterPlanModeToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 进入计划模式工具（F-P1-2 升级）。
 *
 * 切换到 Plan Mode 后：
 * - PermissionMode 设为 "plan"，PermissionChecker 会拦截所有写操作；
 * - 创建一个新的 {@link ExecutionPlan}，后续 LLM 输出的工具调用会被转化为
 *   PlanStep 记录，而不会真正执行；
 * - 用户通过 /plan 查看、/approve [n] 确认后再执行。
 *
 * 注意：当前 ExecutionPlan 通过静态字段共享（单会话架构下足够），
 * 多会话场景应迁移到 AppStateStore。
 */
public class EnterPlanModeTool extends BaseTool<EnterPlanModeToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(EnterPlanModeTool.class);
    private final Settings settings;

    /** 当前活跃的执行计划，Plan Mode 退出时清空。 */
    private static volatile ExecutionPlan activePlan;

    public EnterPlanModeTool(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String getName() {
        return "enter_plan_mode";
    }

    @Override
    public String getDescription() {
        return "切换到计划模式：LLM 输出的工具调用会被记录为执行计划，而非立即执行。使用 /plan 查看、/approve 确认。";
    }

    @Override
    public Class<EnterPlanModeToolInput> getInputClass() {
        return EnterPlanModeToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(EnterPlanModeToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                settings.setPermissionMode("plan");
                // FP-2：模式切换必须同步到运行时 PermissionChecker，否则即使改了 Settings
                // 工具执行链用的仍然是旧模式，写操作依然能通过。
                PermissionChecker checker = context != null ? context.getPermissionChecker() : null;
                if (checker != null) {
                    checker.setMode(PermissionMode.PLAN);
                } else {
                    logger.warn("进入 Plan Mode 时未拿到运行时 PermissionChecker，模式切换可能未生效");
                }
                activePlan = new ExecutionPlan();
                logger.info("已进入 Plan Mode，创建新的执行计划");
                return ToolResult.success(
                        "已切换到计划模式。\n"
                        + "- LLM 的工具调用将被记录为执行计划，而非立即执行。\n"
                        + "- 使用 /plan 查看当前计划。\n"
                        + "- 使用 /approve <步骤号> 批准单步，或 /approve_all 批准全部。\n"
                        + "- 使用 /exit_plan_mode 退出计划模式。");
            } catch (Exception e) {
                logger.error("切换模式失败", e);
                return ToolResult.error("切换模式失败: " + e.getMessage());
            }
        });
    }

    /** 获取当前活跃的执行计划；非 Plan Mode 时返回 null。 */
    public static ExecutionPlan getActivePlan() {
        return activePlan;
    }

    /** 清空执行计划（由 ExitPlanModeTool 调用）。 */
    public static void clearPlan() {
        activePlan = null;
    }
}
