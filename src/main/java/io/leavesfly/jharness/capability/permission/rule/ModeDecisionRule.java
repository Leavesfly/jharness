package io.leavesfly.jharness.capability.permission.rule;

import io.leavesfly.jharness.capability.permission.PermissionContext;
import io.leavesfly.jharness.capability.permission.PermissionDecision;
import io.leavesfly.jharness.capability.permission.PermissionRule;

import java.util.Optional;

/**
 * 模式决策规则（链路终点）。
 *
 * 严格保留原 PermissionChecker 的语义：
 * - FULL_AUTO：黑名单/路径 deny 已在前面过滤，这里直接放行
 * - PLAN：读操作放行，写操作一律拒绝；白名单/路径 allow 都不能绕过
 * - DEFAULT：路径 allow 或工具白名单可跳过"用户确认"，否则非只读 → requiresConfirmation
 */
public class ModeDecisionRule implements PermissionRule {

    @Override
    public Optional<PermissionDecision> evaluate(PermissionContext ctx) {
        boolean toolAllowListed = !ctx.getAllowedTools().isEmpty()
                && ctx.getAllowedTools().contains(ctx.getToolName());

        switch (ctx.getMode()) {
            case FULL_AUTO:
                return Optional.of(PermissionDecision.allow());

            case PLAN:
                if (ctx.isReadOnly()) {
                    return Optional.of(PermissionDecision.allow());
                }
                return Optional.of(PermissionDecision.deny("计划模式阻止所有写入操作"));

            case DEFAULT:
            default:
                if (ctx.isReadOnly() || ctx.isPathAllowHit() || toolAllowListed) {
                    return Optional.of(PermissionDecision.allow());
                }
                return Optional.of(PermissionDecision.requiresConfirmation("非只读操作需要确认"));
        }
    }
}
