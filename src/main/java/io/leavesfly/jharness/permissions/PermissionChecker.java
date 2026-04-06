package io.leavesfly.jharness.permissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
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
        if (filePath != null) {
            for (PathRule rule : pathRules) {
                if (rule.matches(filePath)) {
                    if (!rule.isAllow()) {
                        return PermissionDecision.deny("路径 " + filePath + " 被规则拒绝: " + rule.getPattern());
                    }
                }
            }
        }

        // 4. 检查工具白名单（在安全检查之后）
        if (!allowedTools.isEmpty() && allowedTools.contains(toolName)) {
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
}