package io.leavesfly.jharness.tools;

import org.slf4j.Logger;
import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.input.EnterWorktreeToolInput;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 进入 Git Worktree 工具
 */
public class EnterWorktreeTool extends BaseTool<EnterWorktreeToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(EnterWorktreeTool.class);

    @Override
    public String getName() {
        return "enter_worktree";
    }

    @Override
    public String getDescription() {
        return "创建并进入 git worktree";
    }

    @Override
    public Class<EnterWorktreeToolInput> getInputClass() {
        return EnterWorktreeToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(EnterWorktreeToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String branch = input.getBranch();
                String path = input.getPath();

                if (branch == null || branch.isEmpty()) {
                    return ToolResult.error("分支名称不能为空");
                }

                String worktreePath = path != null ? path : "../" + branch;

                // 执行 git worktree add 命令
                ProcessBuilder pb = new ProcessBuilder("git", "worktree", "add", worktreePath, branch);
                pb.directory(context.getCwd().toFile());
                pb.redirectErrorStream(true);

                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    return ToolResult.error("创建 worktree 失败: " + output.trim());
                }

                return ToolResult.success("已创建 worktree:\n  分支: " + branch + "\n  路径: " + worktreePath);
            } catch (Exception e) {
                logger.error("创建 worktree 失败", e);
                return ToolResult.error("创建 worktree 失败: " + e.getMessage());
            }
        });
    }
}
