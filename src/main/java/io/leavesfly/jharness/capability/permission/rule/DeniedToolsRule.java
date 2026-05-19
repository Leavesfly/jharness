package io.leavesfly.jharness.capability.permission.rule;

import io.leavesfly.jharness.capability.permission.PermissionContext;
import io.leavesfly.jharness.capability.permission.PermissionDecision;
import io.leavesfly.jharness.capability.permission.PermissionRule;

import java.util.Optional;

/**
 * 工具黑名单规则（全模式生效）。
 */
public class DeniedToolsRule implements PermissionRule {

    @Override
    public Optional<PermissionDecision> evaluate(PermissionContext ctx) {
        if (ctx.getDeniedTools().contains(ctx.getToolName())) {
            return Optional.of(PermissionDecision.deny("工具 " + ctx.getToolName() + " 已被禁用"));
        }
        return Optional.empty();
    }
}
