package io.leavesfly.jharness.capability.permission;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 命令模式匹配与规范化的纯函数集合。
 *
 * 抽离自原 PermissionChecker，方便独立测试与复用。
 */
public final class CommandPatternMatcher {

    private CommandPatternMatcher() {}

    /**
     * 安全的 glob 风格匹配（仅支持 * 与 ?），避免正则注入。
     */
    public static boolean matches(String command, String pattern) {
        if (command == null || pattern == null) {
            return false;
        }
        String regex = globToRegex(pattern);
        try {
            return Pattern.matches(regex, command);
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * 对命令做最小规范化，抗常见绕过：去引号、$IFS、连续空白归一。
     * 非完整 shell 词法分析，仅兜常见手法。
     */
    public static String normalize(String command) {
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

    private static String quoteLiteral(String s) {
        if (!s.contains("\\E")) {
            return "\\Q" + s + "\\E";
        }
        return "\\Q" + s.replace("\\E", "\\E\\\\E\\Q") + "\\E";
    }
}
