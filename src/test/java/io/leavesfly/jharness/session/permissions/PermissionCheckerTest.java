package io.leavesfly.jharness.session.permissions;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖本轮功能性权限修复的核心场景：FP-6、FP-8、FP-12、FP-15。
 *
 * - FP-6：PLAN 模式下，即使工具在 allowedTools 白名单，写操作仍必须被拒绝；
 * - FP-12：路径规则 allow 命中时提前放行，不再走"非只读需确认"分支；
 * - FP-15：并发 add/evaluate 不抛 ConcurrentModificationException；
 * - FP-8：审计日志由 logger 输出，这里仅断言不抛异常（日志内容由 logback 配置消费）。
 */
class PermissionCheckerTest {

    @Test
    void deniedToolAlwaysBlockedEvenInFullAuto() {
        PermissionChecker checker = new PermissionChecker(PermissionMode.FULL_AUTO);
        checker.addDeniedTool("bash");

        PermissionDecision d = checker.evaluate("bash", false, null, "echo hi");
        assertFalse(d.isAllowed(), "工具黑名单应在 FULL_AUTO 下也生效");
        assertFalse(d.isRequiresConfirmation());
    }

    @Test
    void fp6_planModeBlocksWritesEvenForAllowListedTool() {
        // FP-6：白名单不能放行 PLAN 模式下的写操作
        PermissionChecker checker = new PermissionChecker(PermissionMode.PLAN);
        checker.addAllowedTool("file_write");

        PermissionDecision write = checker.evaluate("file_write", false, "/tmp/a.txt", null);
        assertFalse(write.isAllowed(), "PLAN 模式下写操作不应因白名单而放行");
        assertFalse(write.isRequiresConfirmation());

        PermissionDecision read = checker.evaluate("file_read", true, "/tmp/a.txt", null);
        assertTrue(read.isAllowed(), "PLAN 模式下只读操作应放行");
    }

    @Test
    void fp12_allowPathRuleBypassesConfirmationInDefault() {
        // FP-12：DEFAULT 模式下，路径 allow 规则命中时，非只读写操作也直接放行，不再走"需确认"分支
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT);
        checker.addPathRule("**/scratch/**", true);

        PermissionDecision hit = checker.evaluate(
                "file_write", false, "/tmp/scratch/a.txt", null);
        assertTrue(hit.isAllowed(),
                "命中 allow 规则应直接放行，而不是 requiresConfirmation");
        assertFalse(hit.isRequiresConfirmation());

        // 未命中则按 DEFAULT 规则走需确认
        PermissionDecision miss = checker.evaluate(
                "file_write", false, "/tmp/other/b.txt", null);
        assertFalse(miss.isAllowed());
        assertTrue(miss.isRequiresConfirmation(),
                "未命中任何规则的写操作应 requiresConfirmation");
    }

    @Test
    void fp12_denyPathRuleAlwaysBlocks() {
        // 路径 deny 规则在 FULL_AUTO 下也必须生效
        PermissionChecker checker = new PermissionChecker(PermissionMode.FULL_AUTO);
        checker.addPathRule("/etc/**", false);

        PermissionDecision d = checker.evaluate("file_write", false, "/etc/passwd", null);
        assertFalse(d.isAllowed());
        assertFalse(d.isRequiresConfirmation());
        assertNotNull(d.getReason());
    }

    @Test
    void fp12_planModeIgnoresAllowPathRuleForWrites() {
        // FP-12 + FP-6 交叉：PLAN 模式下即使路径 allow 命中，写操作仍被拒绝
        PermissionChecker checker = new PermissionChecker(PermissionMode.PLAN);
        checker.addPathRule("**/scratch/**", true);

        PermissionDecision d = checker.evaluate(
                "file_write", false, "/tmp/scratch/a.txt", null);
        assertFalse(d.isAllowed(),
                "PLAN 模式下路径 allow 规则不应放行写操作");
    }

    @Test
    void deniedCommandExactMatchBlocks() {
        // 现有 matchesCommandPattern 使用 Pattern.matches 全匹配；精确模式可阻断
        PermissionChecker checker = new PermissionChecker(PermissionMode.FULL_AUTO);
        checker.addDeniedCommand("rm -rf /tmp/x");

        assertFalse(checker.evaluate("bash", false, null, "rm -rf /tmp/x").isAllowed(),
                "命令被精确命中应被拒绝");
        // 规范化通道：带引号的等价命令去引号后也应被命中
        assertFalse(checker.evaluate("bash", false, null, "'rm' -rf /tmp/x").isAllowed(),
                "规范化后去引号应能识别引号变形");
        // 非命中命令应放行
        assertTrue(checker.evaluate("bash", false, null, "echo safe").isAllowed());
    }

    @Test
    void fp16_globWildcardInDeniedCommandActuallyWorks() {
        // FP-16：修复前 glob "rm -rf *" 会退化为字面量匹配（只对 "rm -rf *" 本身生效），
        // 这里验证修复后通配符能真正匹配任意参数。
        PermissionChecker checker = new PermissionChecker(PermissionMode.FULL_AUTO);
        checker.addDeniedCommand("rm -rf *");

        assertFalse(checker.evaluate("bash", false, null, "rm -rf /tmp/x").isAllowed(),
                "glob 模式 'rm -rf *' 应匹配 'rm -rf /tmp/x'");
        assertFalse(checker.evaluate("bash", false, null, "rm -rf a b c").isAllowed(),
                "glob 模式 'rm -rf *' 应匹配 'rm -rf a b c'");
        // 经规范化后的变形也应被命中
        assertFalse(checker.evaluate("bash", false, null, "'rm' -rf /home").isAllowed(),
                "规范化去引号后 glob 通配仍应生效");
        // 非命中命令应放行
        assertTrue(checker.evaluate("bash", false, null, "ls -al").isAllowed());
    }

    @Test
    void fp16_globSudoWildcardMatchesAnySudoInvocation() {
        PermissionChecker checker = new PermissionChecker(PermissionMode.FULL_AUTO);
        checker.addDeniedCommand("sudo *");

        assertFalse(checker.evaluate("bash", false, null, "sudo rm /tmp/a").isAllowed());
        assertFalse(checker.evaluate("bash", false, null, "sudo apt update").isAllowed());
        assertTrue(checker.evaluate("bash", false, null, "echo sudo").isAllowed(),
                "非 sudo 开头的命令不应被 'sudo *' 命中");
    }

    @Test
    void fp16_questionMarkMatchesSingleChar() {
        PermissionChecker checker = new PermissionChecker(PermissionMode.FULL_AUTO);
        checker.addDeniedCommand("rm ?");

        assertFalse(checker.evaluate("bash", false, null, "rm a").isAllowed(),
                "? 应匹配单字符");
        assertTrue(checker.evaluate("bash", false, null, "rm ab").isAllowed(),
                "? 不应匹配多字符");
    }

    @Test
    void fp16_regexMetacharsInPatternAreEscaped() {
        // FP-16：pattern 中的正则元字符（如 .、(、+）应被当字面量处理，不能产生正则注入
        PermissionChecker checker = new PermissionChecker(PermissionMode.FULL_AUTO);
        checker.addDeniedCommand("echo (a.b)+ *");

        assertFalse(checker.evaluate("bash", false, null, "echo (a.b)+ xyz").isAllowed(),
                "正则元字符应按字面量匹配");
        assertTrue(checker.evaluate("bash", false, null, "echo aXb xyz").isAllowed(),
                "把 '.' 当成正则的 '.' 会误伤；修复后应按字面量匹配，不应命中");
    }

    @Test
    void setModeIsVisibleToSubsequentEvaluate() {
        // FP-2 的运行时语义：setMode 之后，后续 evaluate 立即按新模式执行
        PermissionChecker checker = new PermissionChecker(PermissionMode.FULL_AUTO);

        assertTrue(checker.evaluate("file_write", false, "/tmp/a", null).isAllowed(),
                "FULL_AUTO 模式应放行写操作");

        checker.setMode(PermissionMode.PLAN);

        assertFalse(checker.evaluate("file_write", false, "/tmp/a", null).isAllowed(),
                "切到 PLAN 之后写操作应立即被拒绝");
    }

    @Test
    void fp15_concurrentAddAndEvaluateDoesNotThrow() throws Exception {
        // FP-15：并发 addPathRule / evaluate 不能抛 ConcurrentModificationException
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger failures = new AtomicInteger(0);
        int rounds = 500;
        CountDownLatch done = new CountDownLatch(rounds * 2);

        try {
            for (int i = 0; i < rounds; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        checker.addPathRule("**/scratch" + idx + "/**", true);
                    } catch (Throwable t) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
                pool.submit(() -> {
                    try {
                        checker.evaluate("file_write", false,
                                "/tmp/scratch" + idx + "/x", null);
                    } catch (Throwable t) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(20, TimeUnit.SECONDS), "并发测试应在超时前完成");
            assertEquals(0, failures.get(), "并发 add/evaluate 不应抛异常");
        } finally {
            pool.shutdownNow();
        }
    }
}
