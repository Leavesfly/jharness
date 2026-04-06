package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.BashToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bash 工具
 *
 * 执行 shell 命令并返回输出结果。
 */
public class BashTool extends BaseTool<BashToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(BashTool.class);
    private static final int MAX_OUTPUT_LENGTH = 10000;

    /** 高危命令黑名单：禁止直接执行的危险命令模式 */
    private static final List<String> DANGEROUS_COMMAND_PATTERNS = List.of(
        "rm\\s+-rf\\s+/[^.]",    // rm -rf / (根目录)
        "mkfs\\.",                // 格式化磁盘
        "dd\\s+if=",             // 低级磁盘操作
        ":(){ :|:& };:",         // fork 炸弹
        "chmod\\s+-R\\s+777\\s+/", // 全局权限修改
        "shutdown",              // 关机
        "reboot",                // 重启
        "init\\s+0"              // 关机
    );

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public String getDescription() {
        return "执行 shell 命令并返回输出结果。支持所有标准 Unix 命令。";
    }

    @Override
    public Class<BashToolInput> getInputClass() {
        return BashToolInput.class;
    }

    @Override
    public CompletableFuture<ToolResult> execute(BashToolInput input, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String command = input.getCommand();
                if (command == null || command.isBlank()) {
                    return ToolResult.error("命令不能为空");
                }

                // 安全检查：拒绝高危命令
                String dangerousMatch = detectDangerousCommand(command);
                if (dangerousMatch != null) {
                    logger.warn("拒绝执行高危命令: {}", command);
                    return ToolResult.error("安全限制: 检测到高危命令模式，已拒绝执行");
                }

                logger.debug("执行命令: {}", command);

                ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                pb.directory(context.getCwd().toFile());
                pb.redirectErrorStream(true);

                Process process = pb.start();

                // 读取输出
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                // 等待完成（带超时）
                boolean finished = process.waitFor(input.getTimeout(), TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return ToolResult.error("命令执行超时 (" + input.getTimeout() + "秒)");
                }

                int exitCode = process.exitValue();
                String result = output.toString();

                // 截断过长输出
                if (result.length() > MAX_OUTPUT_LENGTH) {
                    result = result.substring(0, MAX_OUTPUT_LENGTH) + "\n...(输出已截断)";
                }

                String finalResult = String.format("退出码: %d\n输出:\n%s", exitCode, result);
                return new ToolResult(finalResult, exitCode != 0);

            } catch (IOException | InterruptedException e) {
                logger.error("命令执行失败", e);
                Thread.currentThread().interrupt();
                return ToolResult.error("命令执行失败: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isReadOnly(BashToolInput input) {
        if (input.getCommand() == null) {
            return false;
        }
        String cmd = input.getCommand().toLowerCase();
        // 已知的只读命令前缀
        Set<String> readOnlyPrefixes = Set.of(
            "ls", "cat", "head", "tail", "grep", "find", "wc", "echo",
            "pwd", "whoami", "date", "env", "printenv", "which", "type",
            "file", "stat", "du", "df", "uname", "id", "ps", "top",
            "git log", "git status", "git diff", "git show", "git branch"
        );
        String trimmedCmd = cmd.trim();
        return readOnlyPrefixes.stream().anyMatch(prefix ->
            trimmedCmd.equals(prefix) || trimmedCmd.startsWith(prefix + " ") || trimmedCmd.startsWith(prefix + "\t")
        );
    }

    /**
     * 检测高危命令模式，返回匹配的模式或 null
     */
    private static String detectDangerousCommand(String command) {
        for (String pattern : DANGEROUS_COMMAND_PATTERNS) {
            if (command.matches(".*" + pattern + ".*")) {
                return pattern;
            }
        }
        return null;
    }
}
