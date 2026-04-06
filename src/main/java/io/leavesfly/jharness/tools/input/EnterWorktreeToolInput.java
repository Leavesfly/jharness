package io.leavesfly.jharness.tools.input;

import jakarta.validation.constraints.NotBlank;

/**
 * 进入 Worktree 工具输入
 */
public class EnterWorktreeToolInput {
    @NotBlank
    private String branch;
    private String path;

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}
