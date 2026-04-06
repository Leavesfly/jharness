package io.leavesfly.jharness.tools;

import org.slf4j.Logger;
import io.leavesfly.jharness.tools.ToolResult;
import io.leavesfly.jharness.tools.ToolExecutionContext;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.input.ExitWorktreeToolInput;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 退出 Git Worktree 工具
 */
public class ExitWorktreeTool extends BaseTool<ExitWorktreeToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(ExitWorktreeTool.class);

    @Override
    public String getName() {
        return "exit_worktree";
    }

    @Override
    public String getDescription() {
        return "删除 git worktree";
    }

    @Override
    public Class<ExitWorktreeToolInput> getInputClass() {
        return ExitWorktreeToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(ExitWorktreeToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String path = input.getPath();
                if (path == null || path.isEmpty()) {
                    return ToolResult.error("worktree 路径不能为空");
                }

                // 执行 git worktree remove 命令
                ProcessBuilder pb = new ProcessBuilder("git", "worktree", "remove", path);
                pb.directory(context.getCwd().toFile());
                pb.redirectErrorStream(true);

                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    return ToolResult.error("删除 worktree 失败: " + output.trim());
                }

                return ToolResult.success("已删除 worktree: " + path);
            } catch (Exception e) {
                logger.error("删除 worktree 失败", e);
                return ToolResult.error("删除 worktree 失败: " + e.getMessage());
            }
        });
    }
}
