package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * Web 获取工具输入
 */
public class WebFetchToolInput {
    @NotBlank(message = "url 不能为空")
    private String url;

    private Integer max_length = 5000;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Integer getMax_length() {
        return max_length;
    }

    public void setMax_length(Integer max_length) {
        this.max_length = max_length;
    }
}
