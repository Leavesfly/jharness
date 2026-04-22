package io.leavesfly.jharness.tools;

import org.slf4j.Logger;
import io.leavesfly.jharness.tools.input.EnterWorktreeToolInput;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 进入 Git Worktree 工具
 */
public class EnterWorktreeTool extends BaseTool<EnterWorktreeToolInput> {

    private static final Logger logger = LoggerFactory.getLogger(EnterWorktreeTool.class);

    /** git worktree 命令超时（秒）。 */
    private static final int GIT_COMMAND_TIMEOUT_SECONDS = 60;

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
            Process process = null;
            try {
                String branch = input.getBranch();
                String path = input.getPath();

                if (branch == null || branch.isEmpty()) {
                    return ToolResult.error("分支名称不能为空");
                }

                String worktreePath = path != null ? path : "../" + branch;

                // 使用参数数组形式执行 git 命令，天然防止 shell 注入
                ProcessBuilder pb = new ProcessBuilder("git", "worktree", "add", worktreePath, branch);
                pb.directory(context.getCwd().toFile());
                pb.redirectErrorStream(true);

                process = pb.start();
                // 读取输出（与 waitFor 合用时需保证超时后能解除阻塞）
                byte[] outBytes = process.getInputStream().readAllBytes();
                String output = new String(outBytes, StandardCharsets.UTF_8);

                if (!process.waitFor(GIT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return ToolResult.error(
                            "创建 worktree 超时 (" + GIT_COMMAND_TIMEOUT_SECONDS + "s)，已强制终止");
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    return ToolResult.error("创建 worktree 失败 (exit=" + exitCode + "): " + output.trim());
                }

                return ToolResult.success("已创建 worktree:\n  分支: " + branch + "\n  路径: " + worktreePath);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                return ToolResult.error("创建 worktree 被中断");
            } catch (Exception e) {
                logger.error("创建 worktree 失败", e);
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                return ToolResult.error("创建 worktree 失败: " + e.getMessage());
            }
        });
    }
}
