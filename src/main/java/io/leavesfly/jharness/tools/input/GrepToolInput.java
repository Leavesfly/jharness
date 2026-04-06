package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * Grep 搜索工具输入
 */
public class GrepToolInput {
    @NotBlank(message = "pattern 不能为空")
    private String pattern;

    private String path = ".";
    private Boolean include_hidden = false;
    private String type;
    private String glob;
    private Integer output_mode;

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Boolean getInclude_hidden() {
        return include_hidden;
    }

    public void setInclude_hidden(Boolean include_hidden) {
        this.include_hidden = include_hidden;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getGlob() {
        return glob;
    }

    public void setGlob(String glob) {
        this.glob = glob;
    }

    public Integer getOutput_mode() {
        return output_mode;
    }

    public void setOutput_mode(Integer output_mode) {
        this.output_mode = output_mode;
    }
}
