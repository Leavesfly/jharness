package io.leavesfly.jharness.capability.permission;

import java.util.Optional;

/**
 * 权限规则链的单个环节。
 *
 * 返回 {@link Optional#empty()} 表示"本规则不做决策、交给下一环"，
 * 返回非空 {@link PermissionDecision} 即为终态，链路停止。
 *
 * 规则链顺序由 {@link PermissionChecker} 编排，严格保持原先的：
 * 工具黑名单 → 命令黑名单 → 路径规则 → 模式决策。
 */
public interface PermissionRule {
    Optional<PermissionDecision> evaluate(PermissionContext ctx);
}
