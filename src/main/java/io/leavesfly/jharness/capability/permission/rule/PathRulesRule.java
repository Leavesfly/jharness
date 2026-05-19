package io.leavesfly.jharness.capability.permission.rule;

import io.leavesfly.jharness.capability.permission.PathNormalizer;
import io.leavesfly.jharness.capability.permission.PathRule;
import io.leavesfly.jharness.capability.permission.PermissionContext;
import io.leavesfly.jharness.capability.permission.PermissionDecision;
import io.leavesfly.jharness.capability.permission.PermissionRule;

import java.util.Optional;

/**
 * 路径规则评估（全模式生效）。
 *
 * - 命中 deny 规则：立即拒绝
 * - 命中 allow 规则：标记 ctx.pathAllowHit = true，交后续模式规则继续判定
 *   （PLAN 模式下，写操作仍必须被阻断，不被本环允许通过）
 *
 * 使用 realPath 解符号链接，同时尝试用 normalize 字符串匹配，抗 TOCTOU 与路径穿越。
 */
public class PathRulesRule implements PermissionRule {

    @Override
    public Optional<PermissionDecision> evaluate(PermissionContext ctx) {
        String filePath = ctx.getFilePath();
        if (filePath == null) {
            return Optional.empty();
        }
        String normalizedPath = PathNormalizer.normalize(filePath);
        String realPath = PathNormalizer.resolveRealPath(filePath);
        for (PathRule rule : ctx.getPathRules()) {
            boolean hit = rule.matches(filePath)
                    || rule.matches(normalizedPath)
                    || (realPath != null && rule.matches(realPath));
            if (!hit) {
                continue;
            }
            if (!rule.isAllow()) {
                return Optional.of(PermissionDecision.deny(
                        "路径 " + filePath + " 被规则拒绝: " + rule.getPattern()));
            }
            ctx.markPathAllowHit();
            break;
        }
        return Optional.empty();
    }
}
