package io.leavesfly.jharness.tools.input;

/**
 * 文件搜索工具输入
 */
public class GlobToolInput {
    private String path = ".";
    private String pattern;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }
}
