package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.tools.input.EnterPlanModeToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 进入计划模式工具
 */
public class EnterPlanModeTool extends BaseTool<EnterPlanModeToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(EnterPlanModeTool.class);
    private final Settings settings;

    public EnterPlanModeTool(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String getName() {
        return "enter_plan_mode";
    }

    @Override
    public String getDescription() {
        return "切换到计划模式，阻止所有文件写入操作";
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
                settings.save();
                return ToolResult.success("已切换到计划模式。所有文件写入操作将被阻止。");
            } catch (Exception e) {
                logger.error("切换模式失败", e);
                return ToolResult.error("切换模式失败: " + e.getMessage());
            }
        });
    }
}
