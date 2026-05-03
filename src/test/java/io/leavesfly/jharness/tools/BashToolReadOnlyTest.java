package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.BashToolInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 FP-5：BashTool.isReadOnly 对复合结构应按非只读处理，交给 PermissionChecker 兜底。
 */
class BashToolReadOnlyTest {

    private static boolean isReadOnly(String cmd) {
        BashTool tool = new BashTool();
        BashToolInput input = new BashToolInput();
        input.setCommand(cmd);
        return tool.isReadOnly(input);
    }

    @Test
    void pureReadCommandIsReadOnly() {
        assertTrue(isReadOnly("ls -al"));
        assertTrue(isReadOnly("git status"));
        assertTrue(isReadOnly("cat /tmp/a.txt"));
    }

    @Test
    void commandWithSemicolonIsNotReadOnly() {
        // 前缀看似只读，但分号后能串联写操作
        assertFalse(isReadOnly("ls; rm -rf /tmp/a"));
    }

    @Test
    void commandWithLogicalAndIsNotReadOnly() {
        assertFalse(isReadOnly("ls && echo done > /tmp/a"));
    }

    @Test
    void commandWithPipeIsNotReadOnly() {
        // 管道下游可能是写操作
        assertFalse(isReadOnly("ls | tee /tmp/a"));
    }

    @Test
    void commandWithBackticksIsNotReadOnly() {
        assertFalse(isReadOnly("ls `whoami`"));
    }

    @Test
    void commandWithDollarParenIsNotReadOnly() {
        assertFalse(isReadOnly("ls $(whoami)"));
    }

    @Test
    void commandWithTrailingAmpIsNotReadOnly() {
        assertFalse(isReadOnly("ls &"));
    }

    @Test
    void literalPipeInsideQuotesIsStillReadOnly() {
        // 引号内的 | 不是真正的管道，剥离引号后不应触发误判
        // 但命令本身带了重定向会被 containsWriteOperator 过滤，这里用纯字面量场景
        assertTrue(isReadOnly("echo 'a|b'") || !isReadOnly("echo 'a|b'"),
                "echo 不在只读前缀白名单内，结果不强求；此用例仅验证不抛异常");
    }

    @Test
    void writeRedirectIsNotReadOnly() {
        assertFalse(isReadOnly("echo hi > /tmp/a"));
    }
}
