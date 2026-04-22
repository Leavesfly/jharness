package io.leavesfly.jharness.session.permissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * 权限检查器
 *
 * 根据权限模式、路径规则和命令黑名单评估工具执行的权限决策。
 */
public class PermissionChecker {
    private static final Logger logger = LoggerFactory.getLogger(PermissionChecker.class);

    private PermissionMode mode;
    private final List<PathRule> pathRules = new ArrayList<>();
    private final Set<String> deniedCommands = new HashSet<>();
    private final Set<String> allowedTools = new HashSet<>();
    private final Set<String> deniedTools = new HashSet<>();

    public PermissionChecker(PermissionMode mode) {
        this.mode = mode;
    }

    /**
     * 评估权限决策
     *
     * @param toolName   工具名称
     * @param readOnly   是否为只读操作
     * @param filePath   涉及的文件路径（如果有）
     * @param command    涉及的命令（如果有）
     * @return 权限决策结果
     */
    public PermissionDecision evaluate(String toolName, boolean readOnly, String filePath, String command) {
        // 1. 检查工具黑名单（黑名单优先于白名单，防止绕过）
        if (deniedTools.contains(toolName)) {
            return PermissionDecision.deny("工具 " + toolName + " 已被禁用");
        }

        // 2. 检查命令黑名单（在白名单之前检查，防止通过白名单绕过命令限制）
        if (command != null) {
            for (String pattern : deniedCommands) {
                if (matchesCommandPattern(command, pattern)) {
                    return PermissionDecision.deny("命令被拒绝: " + command);
                }
            }
        }

        // 3. 检查路径规则（在白名单之前检查，防止通过白名单绕过路径限制）
        //    P2-M9：对路径做规范化再匹配，避免通过 "a/../b"、"./" 等变形绕过规则
        if (filePath != null) {
            String normalizedPath = normalizeForRuleMatch(filePath);
            for (PathRule rule : pathRules) {
                if (rule.matches(normalizedPath) || rule.matches(filePath)) {
                    if (!rule.isAllow()) {
                        return PermissionDecision.deny("路径 " + filePath + " 被规则拒绝: " + rule.getPattern());
                    }
                }
            }
        }

        // 4. 检查工具白名单（在所有安全检查之后，白名单工具仍受路径和命令规则约束）
        // 注意：白名单仅跳过"模式决策"步骤（如需要用户确认），不跳过黑名单和路径规则
        if (!allowedTools.isEmpty() && allowedTools.contains(toolName)) {
            // 白名单工具在只读模式下直接允许，非只读模式也允许（白名单语义）
            return PermissionDecision.allow();
        }

        // 5. 根据模式决策
        switch (mode) {
            case FULL_AUTO:
                return PermissionDecision.allow();

            case PLAN:
                if (!readOnly) {
                    return PermissionDecision.deny("计划模式阻止所有写入操作");
                }
                return PermissionDecision.allow();

            case DEFAULT:
            default:
                if (readOnly) {
                    return PermissionDecision.allow();
                }
                return PermissionDecision.requiresConfirmation("非只读操作需要确认");
        }
    }

    /**
     * 添加路径规则
     */
    public void addPathRule(String pattern, boolean allow) {
        pathRules.add(new PathRule(pattern, allow));
    }

    /**
     * 添加拒绝的命令
     */
    public void addDeniedCommand(String pattern) {
        deniedCommands.add(pattern);
    }

    /**
     * 添加允许的工具
     */
    public void addAllowedTool(String toolName) {
        allowedTools.add(toolName);
    }

    /**
     * 添加拒绝的工具
     */
    public void addDeniedTool(String toolName) {
        deniedTools.add(toolName);
    }

    /**
     * 设置权限模式
     */
    public void setMode(PermissionMode mode) {
        this.mode = mode;
    }

    public PermissionMode getMode() {
        return mode;
    }

    /**
     * 安全的命令模式匹配（使用 glob 风格，避免正则注入）
     */
    private boolean matchesCommandPattern(String command, String pattern) {
        // 将 glob 模式转换为安全的正则：先转义所有正则元字符，再替换通配符
        String regex = java.util.regex.Pattern.quote(pattern)
                .replace("\\E*\\Q", "\\E.*\\Q")  // 将 * 替换为 .*
                .replace("\\E?\\Q", "\\E.\\Q");   // 将 ? 替换为 .
        return java.util.regex.Pattern.matches(regex, command);
    }

    /**
     * 用于路径规则匹配的路径规范化（P2-M9）。
     *
     * 使用 Path.normalize 处理 "." / ".." 片段；
     * 保留原始大小写（POSIX 文件系统是大小写敏感的，若规则期望大小写不敏感，由规则自身用 glob 匹配）；
     * 失败时回退到原始字符串，确保匹配链不会因为非法输入而被直接放行。
     */
    private static String normalizeForRuleMatch(String filePath) {
        try {
            return java.nio.file.Paths.get(filePath).normalize().toString();
        } catch (Exception e) {
            logger.debug("路径规范化失败，使用原始字符串匹配: {}", filePath);
            return filePath;
        }
    }
}