package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * 消息摘要工具输入
 */
public class BriefToolInput {
    @NotBlank
    private String text;
    private Integer maxLength;

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Integer getMaxLength() { return maxLength; }
    public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
}
