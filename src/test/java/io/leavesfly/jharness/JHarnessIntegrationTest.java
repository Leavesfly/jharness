package io.leavesfly.jharness;
import io.leavesfly.jharness.tools.builtin.web.WebFetchTool;
import io.leavesfly.jharness.tools.builtin.shell.SleepTool;
import io.leavesfly.jharness.tools.builtin.shell.BashTool;
import io.leavesfly.jharness.tools.builtin.task.TodoWriteTool;
import io.leavesfly.jharness.tools.builtin.file.GrepTool;
import io.leavesfly.jharness.tools.builtin.file.GlobTool;
import io.leavesfly.jharness.tools.builtin.file.FileWriteTool;
import io.leavesfly.jharness.tools.builtin.file.FileReadTool;
import io.leavesfly.jharness.tools.builtin.file.FileEditTool;
import io.leavesfly.jharness.tools.builtin.agent.AskUserQuestionTool;

import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.capability.permission.PermissionChecker;
import io.leavesfly.jharness.capability.permission.PermissionMode;
import io.leavesfly.jharness.tools.*;
import io.leavesfly.jharness.tools.input.file.*;
import io.leavesfly.jharness.tools.input.shell.*;
import io.leavesfly.jharness.tools.input.web.*;
import io.leavesfly.jharness.tools.input.code.*;
import io.leavesfly.jharness.tools.input.task.*;
import io.leavesfly.jharness.tools.input.cron.*;
import io.leavesfly.jharness.tools.input.agent.*;
import io.leavesfly.jharness.tools.input.mcp.*;
import io.leavesfly.jharness.tools.input.mode.*;
import io.leavesfly.jharness.tools.input.config.*;
import io.leavesfly.jharness.tools.input.meta.*;
import io.leavesfly.jharness.tools.input.task.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Paths;

/**
 * JHarness 集成测试
 */
class JHarnessIntegrationTest {

    @Test
    void testToolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        
        // 注册工具
        registry.register(new BashTool());
        registry.register(new FileReadTool());
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        registry.register(new TodoWriteTool());
        registry.register(new SleepTool());
        registry.register(new WebFetchTool());
        registry.register(new AskUserQuestionTool());

        assertEquals(10, registry.size());
        assertTrue(registry.has("bash"));
        assertTrue(registry.has("read_file"));
        assertTrue(registry.has("write_file"));
    }

    @Test
    void testPermissionChecker() {
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT);
        
        // 只读操作应该允许
        assertTrue(checker.evaluate("read_file", true, null, null).isAllowed());
        assertTrue(checker.evaluate("glob", true, null, null).isAllowed());
        
        // 写操作需要确认
        assertTrue(checker.evaluate("write_file", false, null, null).isRequiresConfirmation());
        
        // 全自动模式
        checker.setMode(PermissionMode.FULL_AUTO);
        assertTrue(checker.evaluate("write_file", false, null, null).isAllowed());
        
        // 计划模式
        checker.setMode(PermissionMode.PLAN);
        assertTrue(checker.evaluate("read_file", true, null, null).isAllowed());
        assertFalse(checker.evaluate("write_file", false, null, null).isAllowed());
    }

    @Test
    void testSettings() {
        Settings settings = new Settings();
        
        assertNotNull(settings.getModel());
        assertNotNull(settings.getMaxTokens());
        assertEquals(4096, settings.getMaxTokens());
        assertEquals(PermissionMode.DEFAULT, settings.getPermissionMode());
    }

    @Test
    void testToolExecution() throws Exception {
        // 测试 SleepTool
        SleepTool sleepTool = new SleepTool();
        SleepToolInput input = new SleepToolInput();
        input.setSeconds(1);
        
        ToolResult result = sleepTool.execute(input, 
                new ToolExecutionContext(Paths.get("."), null)).get();
        
        assertNotNull(result);
        assertFalse(result.isError());
    }
}
