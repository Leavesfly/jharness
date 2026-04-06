package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * 团队删除工具输入
 */
public class TeamDeleteToolInput {
    @NotBlank
    private String name;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
