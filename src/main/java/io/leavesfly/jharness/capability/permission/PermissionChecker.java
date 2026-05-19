package io.leavesfly.jharness.capability.permission;

import io.leavesfly.jharness.capability.permission.rule.DeniedCommandsRule;
import io.leavesfly.jharness.capability.permission.rule.DeniedToolsRule;
import io.leavesfly.jharness.capability.permission.rule.ModeDecisionRule;
import io.leavesfly.jharness.capability.permission.rule.PathRulesRule;
import io.leavesfly.jharness.kernel.spi.PermissionGate;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 权限检查编排者。
 *
 * 自身仅负责：持有策略状态（mode/集合/路径规则）、对外开放 add* / setMode / evaluate，
 * 真正的判定逻辑全部下沉到 {@link PermissionRule} 责任链。规则顺序严格保留原实现的语义：
 *
 *   1. {@link DeniedToolsRule}    工具黑名单
 *   2. {@link DeniedCommandsRule} 命令黑名单
 *   3. {@link PathRulesRule}      路径规则（命中 deny 立即拒绝；命中 allow 标记并继续）
 *   4. {@link ModeDecisionRule}   模式决策（FULL_AUTO 放行 / PLAN 阻写 / DEFAULT 写需确认）
 *
 * 即使在 FULL_AUTO 模式下，黑名单与路径规则依然生效——FULL_AUTO 的语义是"不弹确认框"，
 * 而不是"放弃安全校验"。
 *
 * 线程安全：集合采用 Copy-On-Write，mode 为 volatile；规则实现无状态，可并发评估。
 */
public class PermissionChecker implements PermissionGate {

    /** 默认规则链。顺序敏感，不可重排。 */
    private static final List<PermissionRule> DEFAULT_RULES = List.of(
            new DeniedToolsRule(),
            new DeniedCommandsRule(),
            new PathRulesRule(),
            new ModeDecisionRule());

    private volatile PermissionMode mode;
    private final List<PathRule> pathRules = new CopyOnWriteArrayList<>();
    private final Set<String> deniedCommands = new CopyOnWriteArraySet<>();
    private final Set<String> allowedTools = new CopyOnWriteArraySet<>();
    private final Set<String> deniedTools = new CopyOnWriteArraySet<>();

    private final List<PermissionRule> rules;

    public PermissionChecker(PermissionMode mode) {
        this(mode, DEFAULT_RULES);
    }

    /** 注入自定义规则链（测试或扩展用）。 */
    public PermissionChecker(PermissionMode mode, List<PermissionRule> rules) {
        this.mode = mode;
        this.rules = List.copyOf(rules);
    }

    /**
     * 评估权限决策：按规则链顺序执行，第一个产出非空决策的规则即为终态。
     */
    public PermissionDecision evaluate(String toolName, boolean readOnly, String filePath, String command) {
        PermissionContext ctx = new PermissionContext(
                toolName, readOnly, filePath, command, mode,
                Collections.unmodifiableSet(deniedTools),
                Collections.unmodifiableSet(deniedCommands),
                Collections.unmodifiableSet(allowedTools),
                Collections.unmodifiableList(pathRules));

        PermissionDecision decision = null;
        for (PermissionRule rule : rules) {
            Optional<PermissionDecision> result = rule.evaluate(ctx);
            if (result.isPresent()) {
                decision = result.get();
                break;
            }
        }
        // 兜底：链路未给出决策视为放行（理论上 ModeDecisionRule 永远会产出决策）
        if (decision == null) {
            decision = PermissionDecision.allow();
        }
        PermissionAuditor.audit(toolName, readOnly, mode, filePath, command, decision);
        return decision;
    }

    public void addPathRule(String pattern, boolean allow) {
        pathRules.add(new PathRule(pattern, allow));
    }

    public void addDeniedCommand(String pattern) {
        deniedCommands.add(pattern);
    }

    public void addAllowedTool(String toolName) {
        allowedTools.add(toolName);
    }

    public void addDeniedTool(String toolName) {
        deniedTools.add(toolName);
    }

    public void setMode(PermissionMode mode) {
        PermissionMode old = this.mode;
        this.mode = mode;
        PermissionAuditor.auditModeChange(old, mode);
    }

    public PermissionMode getMode() {
        return mode;
    }
}