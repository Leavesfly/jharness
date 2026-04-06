package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * 网络搜索工具输入
 */
public class WebSearchToolInput {
    @NotBlank
    public String query;

    public int numResults = 5;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public int getNumResults() { return numResults; }
    public void setNumResults(int numResults) { this.numResults = numResults; }
}
