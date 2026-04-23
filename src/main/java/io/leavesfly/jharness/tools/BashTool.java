package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.BashToolInput;
import io.leavesfly.jharness.tools.shell.ShellSession;
import io.leavesfly.jharness.tools.shell.ShellSessionManager;
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
import java.util.regex.Pattern;

/**
 * Bash 工具
 *
 * 执行 shell 命令并返回输出结果。
 */
public class BashTool extends BaseTool<BashToolInput> {
    private static final Logger logger = LoggerFactory.getLogger(BashTool.class);
    private static final int MAX_OUTPUT_LENGTH = 10000;

    /**
     * 高危命令黑名单：禁止直接执行的危险命令模式
     *
     * 注意：黑名单本质不安全（可通过编码、变量展开、命令替换等绕过），
     * 此处仅作为最后防线。真正的安全保障必须依赖 PermissionChecker + 用户确认。
     * 为提升覆盖度：所有模式均编译为 Pattern 并启用 CASE_INSENSITIVE，
     * 同时新增对命令替换 / eval / base64 解码执行等常见绕过手段的检测。
     */
    private static final List<Pattern> DANGEROUS_COMMAND_PATTERNS = java.util.stream.Stream.of(
        "rm\\s+-rf?\\s+(?:--no-preserve-root\\s+)?/(?![.\\w])", // rm -rf / 根目录及变形
        "mkfs\\.",                              // 格式化磁盘
        "dd\\s+if=.*of=/dev/",                  // 低级磁盘写操作
        ":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:", // fork 炸弹
        "chmod\\s+-R\\s+[0-7]{3,4}\\s+/",      // 全局权限修改
        "\\b(?:shutdown|reboot|halt|poweroff)\\b", // 关机/重启
        "\\binit\\s+0\\b",                     // 关机
        "(?:curl|wget|fetch)\\s[^|;]*\\|\\s*(?:ba)?sh\\b", // 下载并执行
        "\\bsudo\\s+rm\\b",                    // sudo 删除
        ">\\s*/dev/sd[a-z]",                   // 直接写磁盘设备
        "\\bmv\\s+/\\s",                       // 移动根目录
        "\\bchown\\s+-R\\b.*\\s/\\s*$",        // 全局所有权修改
        "\\beval\\s+",                          // eval 执行
        "base64\\s+-d.*\\|\\s*(?:ba)?sh",      // base64 解码后执行
        "`[^`]*`",                              // 反引号命令替换（启发式，会有误报）
        "\\$\\([^)]*\\)"                       // $() 命令替换（启发式，会有误报）
    ).map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE)).toList();

    /**
     * 只读命令前缀白名单
     *
     * 注意：仅凭命令前缀判断只读性并不可靠（如 echo "x" > file），
     * 此处仅作为权限提示，实际安全保障依赖 PermissionChecker。
     */

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

                // 安全检查：限制命令长度，防止超长命令攻击
                if (command.length() > 10000) {
                    return ToolResult.error("安全限制: 命令长度超过限制 (最大 10000 字符)");
                }

                logger.debug("执行命令: {} (session={})", command, input.getSession_id());

                // F-P1-5：若指定了 session_id，则走持久会话路径
                if (input.getSession_id() != null && !input.getSession_id().isBlank()) {
                    return runInSession(input, context);
                }

                // 旧路径：一次性进程
                return runOneShot(input, context);

            } catch (IOException | InterruptedException e) {
                logger.error("命令执行失败", e);
                Thread.currentThread().interrupt();
                return ToolResult.error("命令执行失败: " + e.getMessage());
            }
        });
    }

    /**
     * 一次性进程模式：每次命令独立进程，环境不共享。
     */
    private ToolResult runOneShot(BashToolInput input, ToolExecutionContext context)
            throws IOException, InterruptedException {
        String command = input.getCommand();
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(context.getCwd().toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(input.getTimeout(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return ToolResult.error("命令执行超时 (" + input.getTimeout() + "秒)");
        }

        int exitCode = process.exitValue();
        String result = truncateOutput(output.toString());
        return new ToolResult(
                String.format("退出码: %d\n输出:\n%s", exitCode, result),
                exitCode != 0);
    }

    /**
     * 持久会话模式（F-P1-5）：复用同一个长驻 bash 进程。
     */
    private ToolResult runInSession(BashToolInput input, ToolExecutionContext context) throws IOException {
        ShellSession session = ShellSessionManager.getInstance()
                .getOrCreate(input.getSession_id(), context.getCwd());
        ShellSession.Result result;
        try {
            result = session.run(input.getCommand(), input.getTimeout());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ToolResult.error("命令执行被中断");
        }
        if (result.timedOut()) {
            return ToolResult.error(String.format(
                    "命令在 session=%s 中执行超时 (%d 秒)，会话已自动关闭",
                    input.getSession_id(), input.getTimeout()));
        }
        String out = truncateOutput(result.output());
        return new ToolResult(
                String.format("[session=%s] 退出码: %d\n输出:\n%s",
                        input.getSession_id(), result.exitCode(), out),
                result.exitCode() != 0);
    }

    private static String truncateOutput(String output) {
        if (output.length() > MAX_OUTPUT_LENGTH) {
            return output.substring(0, MAX_OUTPUT_LENGTH) + "\n...(输出已截断)";
        }
        return output;
    }

    @Override
    public boolean isReadOnly(BashToolInput input) {
        if (input.getCommand() == null) {
            return false;
        }
        String command = input.getCommand();
        String trimmedCmd = command.trim().toLowerCase();

        // 如果命令包含重定向或管道写操作，则不是只读
        // 注意：这里只做简单检测，复杂场景仍依赖 PermissionChecker 兜底
        if (containsWriteOperator(command)) {
            return false;
        }

        // 已知的只读命令前缀白名单
        Set<String> readOnlyPrefixes = Set.of(
            "ls", "cat", "head", "tail", "grep", "find", "wc",
            "pwd", "whoami", "date", "env", "printenv", "which", "type",
            "file", "stat", "du", "df", "uname", "id", "ps",
            "git log", "git status", "git diff", "git show", "git branch"
        );
        return readOnlyPrefixes.stream().anyMatch(prefix ->
            trimmedCmd.equals(prefix)
                || trimmedCmd.startsWith(prefix + " ")
                || trimmedCmd.startsWith(prefix + "\t")
        );
    }

    /**
     * 检测命令中是否包含写操作符（重定向、管道写等）
     *
     * 这是一个启发式检测，用于过滤掉明显的写操作命令。
     */
    private static boolean containsWriteOperator(String command) {
        // 检测输出重定向（> 和 >>），排除 >= 和 => 等比较符
        if (command.matches(".*(?<![=><!])>(?!=).*")) {
            return true;
        }
        // 检测 tee 命令（将输出写入文件）
        if (command.matches(".*\\btee\\b.*")) {
            return true;
        }
        return false;
    }

    /**
     * 检测高危命令模式，返回匹配的模式或 null。
     *
     * 使用预编译 Pattern + find() 提升性能（相比每次 matches() 正则编译）。
     */
    private static String detectDangerousCommand(String command) {
        for (Pattern pattern : DANGEROUS_COMMAND_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }
}