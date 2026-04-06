package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * 工具搜索工具输入
 */
public class ToolSearchToolInput {
    @NotBlank
    private String query;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
}
