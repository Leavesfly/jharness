package io.leavesfly.jharness.permissions;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 路径规则
 *
 * 用于匹配文件路径并决定是否允许访问。
 */
public class PathRule {
    private static final Logger logger = LoggerFactory.getLogger(PathRule.class);
    private final String pattern;
    private final boolean allow;
    private final PathMatcher matcher;

    public PathRule(String pattern, boolean allow) {
        this.pattern = pattern;
        this.allow = allow;
        try {
            this.matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException e) {
            logger.error("无效的路径模式: {}", pattern, e);
            throw new IllegalArgumentException("无效的路径模式: " + pattern, e);
        }
    }

    /**
     * 检查路径是否匹配此规则
     *
     * @param path 文件路径
     * @return 如果匹配返回 true
     */
    public boolean matches(String path) {
        return matcher.matches(java.nio.file.Paths.get(path));
    }

    public String getPattern() {
        return pattern;
    }

    public boolean isAllow() {
        return allow;
    }
}