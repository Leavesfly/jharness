package io.leavesfly.jharness.capability.permission.rule;

import io.leavesfly.jharness.capability.permission.CommandPatternMatcher;
import io.leavesfly.jharness.capability.permission.PermissionContext;
import io.leavesfly.jharness.capability.permission.PermissionDecision;
import io.leavesfly.jharness.capability.permission.PermissionRule;

import java.util.Optional;

/**
 * 命令黑名单规则（全模式生效，包括 FULL_AUTO）。
 *
 * 使用规范化后的 token 序列做匹配，防止 "rm${{IFS}}-rf"、"'r'm -rf"、引号变形绕过。
 */
public class DeniedCommandsRule implements PermissionRule {

    @Override
    public Optional<PermissionDecision> evaluate(PermissionContext ctx) {
        String command = ctx.getCommand();
        if (command == null) {
            return Optional.empty();
        }
        String normalized = CommandPatternMatcher.normalize(command);
        for (String pattern : ctx.getDeniedCommands()) {
            if (CommandPatternMatcher.matches(command, pattern)
                    || CommandPatternMatcher.matches(normalized, pattern)) {
                return Optional.of(PermissionDecision.deny("命令被拒绝: " + command));
            }
        }
        return Optional.empty();
    }
}
