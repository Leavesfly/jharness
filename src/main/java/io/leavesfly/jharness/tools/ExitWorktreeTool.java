package io.leavesfly.jharness.tools;

import org.slf4j.Logger;
import io.leavesfly.jharness.tools.input.ExitWorktreeToolInput;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 退出 Git Worktree 工具
 */
public class ExitWorktreeTool extends BaseTool<ExitWorktreeToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(ExitWorktreeTool.class);

    /** git worktree 命令超时（秒）。 */
    private static final int GIT_COMMAND_TIMEOUT_SECONDS = 60;

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
            Process process = null;
            try {
                String path = input.getPath();
                if (path == null || path.isEmpty()) {
                    return ToolResult.error("worktree 路径不能为空");
                }

                ProcessBuilder pb = new ProcessBuilder("git", "worktree", "remove", path);
                pb.directory(context.getCwd().toFile());
                pb.redirectErrorStream(true);

                process = pb.start();
                byte[] outBytes = process.getInputStream().readAllBytes();
                String output = new String(outBytes, StandardCharsets.UTF_8);

                if (!process.waitFor(GIT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return ToolResult.error(
                            "删除 worktree 超时 (" + GIT_COMMAND_TIMEOUT_SECONDS + "s)，已强制终止");
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    return ToolResult.error("删除 worktree 失败 (exit=" + exitCode + "): " + output.trim());
                }

                return ToolResult.success("已删除 worktree: " + path);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                return ToolResult.error("删除 worktree 被中断");
            } catch (Exception e) {
                logger.error("删除 worktree 失败", e);
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                return ToolResult.error("删除 worktree 失败: " + e.getMessage());
            }
        });
    }
}
