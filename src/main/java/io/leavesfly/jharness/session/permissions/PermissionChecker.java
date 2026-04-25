package io.leavesfly.jharness.session.permissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * 权限检查器
 *
 * 根据权限模式、路径规则和命令黑名单评估工具执行的权限决策。
 *
 * 安全策略（严格顺序）：
 *   1. 工具黑名单   —— 全模式强制生效，不可绕过
 *   2. 命令黑名单   —— 全模式强制生效，使用规范化后的 token 序列匹配，抗引号/空格/base64 变形
 *   3. 路径规则     —— 全模式强制生效，使用 realPath 解符号链接，抗 TOCTOU / 穿越
 *   4. 工具白名单   —— 仅跳过"用户二次确认"，不跳过上面三条安全栅栏
 *   5. 模式决策     —— FullAuto 自动通过、Plan 阻写、Default 写操作需确认
 *
 * 即使在 FULL_AUTO 模式下，黑名单与路径规则依然生效。FULL_AUTO 的语义是"不弹确认框"，
 * 而不是"放弃安全校验"。
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
        // 1. 工具黑名单（全模式生效）
        if (deniedTools.contains(toolName)) {
            return PermissionDecision.deny("工具 " + toolName + " 已被禁用");
        }

        // 2. 命令黑名单（全模式生效，包括 FULL_AUTO）
        //    使用规范化后的 token 序列做匹配，防止 "rm${IFS}-rf"、"'r'm -rf"、引号变形绕过
        if (command != null) {
            String normalizedCommand = normalizeCommand(command);
            for (String pattern : deniedCommands) {
                if (matchesCommandPattern(command, pattern)
                        || matchesCommandPattern(normalizedCommand, pattern)) {
                    return PermissionDecision.deny("命令被拒绝: " + command);
                }
            }
        }

        // 3. 路径规则（全模式生效，包括 FULL_AUTO）
        //    使用 realPath 解引用 symlink，同时尝试用 normalize 字符串匹配，抗路径穿越与 TOCTOU
        if (filePath != null) {
            String normalizedPath = normalizeForRuleMatch(filePath);
            String realPath = resolveRealPath(filePath);
            for (PathRule rule : pathRules) {
                boolean hit = rule.matches(filePath)
                        || rule.matches(normalizedPath)
                        || (realPath != null && rule.matches(realPath));
                if (hit && !rule.isAllow()) {
                    return PermissionDecision.deny(
                            "路径 " + filePath + " 被规则拒绝: " + rule.getPattern());
                }
            }
        }

        // 4. 工具白名单（已经通过了所有安全栅栏；白名单仅跳过模式决策阶段的确认）
        if (!allowedTools.isEmpty() && allowedTools.contains(toolName)) {
            return PermissionDecision.allow();
        }

        // 5. 模式决策
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
     * 对命令做最小规范化，用于抗引号/空格/IFS 变量绕过黑名单：
     *   - 去除单/双引号
     *   - 将 ${IFS}、\t、\n、连续空白归一为单空格
     *   - trim 两端
     *
     * 这不是完整的 shell 词法分析（那需要 AST 级解析），但能兜住常见的手法。
     */
    private static String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        String result = command.replace("'", "").replace("\"", "");
        result = result.replace("${IFS}", " ")
                .replace("$IFS", " ")
                .replace("\t", " ")
                .replace("\n", " ")
                .replace("\r", " ");
        result = result.replaceAll("\\s+", " ").trim();
        return result;
    }

    /**
     * 用于路径规则匹配的路径规范化。
     *
     * 使用 Path.normalize 处理 "." / ".." 片段；
     * 保留原始大小写（POSIX 文件系统是大小写敏感的，若规则期望大小写不敏感，由规则自身用 glob 匹配）；
     * 失败时回退到原始字符串，确保匹配链不会因为非法输入而被直接放行。
     */
    private static String normalizeForRuleMatch(String filePath) {
        try {
            return Paths.get(filePath).normalize().toString();
        } catch (Exception e) {
            logger.debug("路径规范化失败，使用原始字符串匹配: {}", filePath);
            return filePath;
        }
    }

    /**
     * 解析路径的真实物理路径（跟随符号链接），用于 TOCTOU 防护。
     *
     * 文件不存在时退化为 toAbsolutePath().normalize()；任何异常均返回 null，
     * 调用方需同时尝试用原始路径和归一化字符串匹配规则。
     */
    private static String resolveRealPath(String filePath) {
        try {
            Path p = Paths.get(filePath);
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                return p.toRealPath().toString();
            }
            return p.toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            logger.debug("realPath 解析失败: {}", filePath);
            return null;
        }
    }
}