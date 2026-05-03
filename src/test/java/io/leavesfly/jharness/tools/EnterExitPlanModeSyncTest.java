package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.session.permissions.PermissionChecker;
import io.leavesfly.jharness.session.permissions.PermissionMode;
import io.leavesfly.jharness.tools.input.EnterPlanModeToolInput;
import io.leavesfly.jharness.tools.input.ExitPlanModeToolInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 FP-2：切到 Plan / 退出 Plan 时，PermissionChecker 的 mode 必须被同步，
 * 不能只改 Settings 而让运行时鉴权器状态漂移。
 */
class EnterExitPlanModeSyncTest {

    @TempDir
    Path tempDir;

    @Test
    void enterPlanModeSyncsCheckerMode() throws Exception {
        Settings settings = new Settings();
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT);
        EnterPlanModeTool tool = new EnterPlanModeTool(settings);

        ToolExecutionContext ctx = new ToolExecutionContext(tempDir, null, checker);
        ToolResult result = tool.execute(new EnterPlanModeToolInput(), ctx).get();

        assertFalse(result.isError());
        assertEquals(PermissionMode.PLAN, settings.getPermissionMode(),
                "Settings 应被切到 PLAN");
        assertEquals(PermissionMode.PLAN, checker.getMode(),
                "PermissionChecker 也应被同步到 PLAN（FP-2）");
    }

    @Test
    void exitPlanModeSyncsCheckerMode() throws Exception {
        Settings settings = new Settings();
        settings.setPermissionMode(PermissionMode.PLAN);
        PermissionChecker checker = new PermissionChecker(PermissionMode.PLAN);
        ExitPlanModeTool tool = new ExitPlanModeTool(settings);

        ToolExecutionContext ctx = new ToolExecutionContext(tempDir, null, checker);
        ToolResult result = tool.execute(new ExitPlanModeToolInput(), ctx).get();

        assertFalse(result.isError());
        assertEquals(PermissionMode.DEFAULT, settings.getPermissionMode());
        assertEquals(PermissionMode.DEFAULT, checker.getMode(),
                "退出 Plan 时 PermissionChecker 也应被同步到 DEFAULT（FP-2）");
    }

    @Test
    void enterPlanModeWithoutCheckerStillUpdatesSettings() throws Exception {
        // 向后兼容：未注入 PermissionChecker 时（老调用方），仍应正常工作
        Settings settings = new Settings();
        EnterPlanModeTool tool = new EnterPlanModeTool(settings);

        ToolExecutionContext ctx = new ToolExecutionContext(tempDir, null);
        ToolResult result = tool.execute(new EnterPlanModeToolInput(), ctx).get();

        assertFalse(result.isError());
        assertEquals(PermissionMode.PLAN, settings.getPermissionMode());
    }
}
