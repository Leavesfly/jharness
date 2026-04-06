package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * 退出 Worktree 工具输入
 */
public class ExitWorktreeToolInput {
    @NotBlank
    private String path;

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}
