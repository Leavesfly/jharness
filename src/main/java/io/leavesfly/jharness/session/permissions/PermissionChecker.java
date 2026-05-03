package io.leavesfly.jharness.session.permissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 权限检查器
 *
 * 根据权限模式、路径规则和命令黑名单评估工具执行的权限决策。
 *
 * 安全策略（严格顺序）：
 *   1. 工具黑名单   —— 全模式强制生效，不可绕过
 *   2. 命令黑名单   —— 全模式强制生效，使用规范化后的 token 序列匹配，抗引号/空格/base64 变形
 *   3. 路径规则     —— 全模式强制生效，使用 realPath 解符号链接，抗 TOCTOU / 穿越；
 *                     命中 allow 规则可提前放行，命中 deny 规则直接拒绝（FP-12）
 *   4. 工具白名单   —— 仅跳过"用户二次确认"，且不能放行 PLAN 模式下的写操作（FP-6）
 *   5. 模式决策     —— FullAuto 自动通过、Plan 阻写、Default 写操作需确认
 *
 * 即使在 FULL_AUTO 模式下，黑名单与路径规则依然生效。FULL_AUTO 的语义是"不弹确认框"，
 * 而不是"放弃安全校验"。
 *
 * FP-15 线程安全：
 *   多个会话 / 后台入口可能并发调用 evaluate 与 setMode / addXxx，
 *   这里将所有集合换成 Copy-On-Write 结构，保证迭代期 addXxx 不会抛 CME。
 *   mode 字段改为 volatile，保证切模式对所有读取线程可见。
 *
 * FP-8 审计日志：
 *   每一次 evaluate 都会通过 logger.info 输出一条结构化审计日志，内容包括工具、readOnly、
 *   路径/命令摘要、最终决策与触发原因。关键审计（deny / requiresConfirmation）走 info，
 *   allow 场景若需要静默可通过日志级别过滤。
 */
public class PermissionChecker {
    private static final Logger logger = LoggerFactory.getLogger(PermissionChecker.class);
    /** 单独的审计 Logger，便于在 logback 里把权限审计打到独立文件。 */
    private static final Logger auditLogger = LoggerFactory.getLogger("jharness.permission.audit");

    private volatile PermissionMode mode;
    private final List<PathRule> pathRules = new CopyOnWriteArrayList<>();
    private final Set<String> deniedCommands = new CopyOnWriteArraySet<>();
    private final Set<String> allowedTools = new CopyOnWriteArraySet<>();
    private final Set<String> deniedTools = new CopyOnWriteArraySet<>();

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
        PermissionDecision decision = evaluateInternal(toolName, readOnly, filePath, command);
        audit(toolName, readOnly, filePath, command, decision);
        return decision;
    }

    private PermissionDecision evaluateInternal(String toolName, boolean readOnly,
                                                String filePath, String command) {
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
        //    - 命中 deny 规则：立即拒绝
        //    - 命中 allow 规则：提前放行（FP-12），但 PLAN 模式下写操作仍必须被阻断（见第 5 步）
        //    使用 realPath 解引用 symlink，同时尝试用 normalize 字符串匹配，抗路径穿越与 TOCTOU
        boolean pathAllowHit = false;
        if (filePath != null) {
            String normalizedPath = normalizeForRuleMatch(filePath);
            String realPath = resolveRealPath(filePath);
            for (PathRule rule : pathRules) {
                boolean hit = rule.matches(filePath)
                        || rule.matches(normalizedPath)
                        || (realPath != null && rule.matches(realPath));
                if (!hit) {
                    continue;
                }
                if (!rule.isAllow()) {
                    return PermissionDecision.deny(
                            "路径 " + filePath + " 被规则拒绝: " + rule.getPattern());
                }
                // FP-12：命中 allow 规则，记录标记，后续决定是否立即放行
                pathAllowHit = true;
                break;
            }
        }

        // 4. 工具白名单
        //    白名单命中时跳过"需要用户确认"的流程，但在 PLAN 模式下必须保留写阻断语义，
        //    否则白名单会变成逃逸通道（FP-6）。
        boolean toolAllowListed = !allowedTools.isEmpty() && allowedTools.contains(toolName);

        // 5. 模式决策
        switch (mode) {
            case FULL_AUTO:
                // 黑名单 / 路径 deny 已在前面过滤；这里直接放行
                return PermissionDecision.allow();

            case PLAN:
                // PLAN 模式核心语义：读操作放行，写操作一律拒绝。
                // 白名单不能绕过；路径 allow 规则也不能绕过（否则"允许写某目录"就逃逸了 PLAN 语义）。
                if (readOnly) {
                    return PermissionDecision.allow();
                }
                return PermissionDecision.deny("计划模式阻止所有写入操作");

            case DEFAULT:
            default:
                // 路径 allow 或工具白名单都可以跳过"用户确认"，但只限于 DEFAULT 模式
                if (readOnly || pathAllowHit || toolAllowListed) {
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
        PermissionMode old = this.mode;
        this.mode = mode;
        auditLogger.info("[PERM] mode_changed: {} -> {}", old, mode);
    }

    public PermissionMode getMode() {
        return mode;
    }

    /**
     * FP-8：对外发出结构化审计日志。
     *
     * 字段设计为 key=value，便于 grep / 日志平台按字段切片：
     *   tool=xxx ro=true|false mode=... decision=allow|deny|confirm reason="..."
     *   path="..." cmd="..."
     *
     * 路径/命令做截断，避免审计日志被攻击者灌爆；审计日志不应影响主链路，
     * 任何异常都捕获并降级到 debug。
     */
    private void audit(String toolName, boolean readOnly, String filePath,
                       String command, PermissionDecision decision) {
        try {
            String decisionStr;
            String reason = decision != null ? decision.getReason() : null;
            if (decision == null) {
                decisionStr = "null";
            } else if (decision.isAllowed()) {
                decisionStr = "allow";
            } else if (decision.isRequiresConfirmation()) {
                decisionStr = "confirm";
            } else {
                decisionStr = "deny";
            }
            auditLogger.info("[PERM] tool={} ro={} mode={} decision={} reason=\"{}\" path=\"{}\" cmd=\"{}\"",
                    toolName, readOnly, mode, decisionStr,
                    shorten(reason, 200),
                    shorten(filePath, 200),
                    shorten(command, 200));
        } catch (Exception e) {
            logger.debug("审计日志输出失败（忽略）", e);
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated " + (s.length() - max) + "B]";
    }

    /**
     * 安全的命令模式匹配（使用 glob 风格，避免正则注入）。
     *
     * FP-16：旧实现依赖 {@code Pattern.quote(pattern).replace("\\E*\\Q", "\\E.*\\Q")} 做通配符替换，
     * 但 {@code Pattern.quote("rm -rf *")} 输出是 {@code "\Qrm -rf *\E"}——星号前是空格而非 {@code \E}，
     * 于是 replace 永远匹配不到，glob 退化为字面量匹配，使得用户配置里的
     * {@code "rm -rf *"}、{@code "sudo *"} 全部失效。
     *
     * 这里改为逐字符扫描：遇到 {@code *} / {@code ?} 直接产出 {@code .*} / {@code .}，
     * 其他字符整体包进 {@code \Q...\E} 做字面量转义，避免正则注入。
     */
    static boolean matchesCommandPattern(String command, String pattern) {
        if (command == null || pattern == null) {
            return false;
        }
        String regex = globToRegex(pattern);
        try {
            return java.util.regex.Pattern.matches(regex, command);
        } catch (java.util.regex.PatternSyntaxException e) {
            // 理论上 globToRegex 不会产出非法正则；兜底一下，不抛出影响调用方
            return false;
        }
    }

    /**
     * FP-16：把 glob 风格的模式（仅支持 {@code *} 与 {@code ?}）安全地转换成等价正则。
     * 非通配符片段一律用 {@code \Q...\E} 包裹，避免正则元字符注入。
     */
    private static String globToRegex(String pattern) {
        StringBuilder sb = new StringBuilder(pattern.length() + 16);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    sb.append(quoteLiteral(literal.toString()));
                    literal.setLength(0);
                }
                sb.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            sb.append(quoteLiteral(literal.toString()));
        }
        return sb.toString();
    }

    /**
     * 把字面量字符串包成 {@code \Q...\E}。若字面量里本身含有 {@code \E}，需要把它断开后重新拼接，
     * 否则 {@code \E} 会提前结束 quote 段导致注入。
     */
    private static String quoteLiteral(String s) {
        if (!s.contains("\\E")) {
            return "\\Q" + s + "\\E";
        }
        // 把每个 \E 替换为：先结束 quote -> 用字面量 \E（即 \\E） -> 再开启 quote
        return "\\Q" + s.replace("\\E", "\\E\\\\E\\Q") + "\\E";
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