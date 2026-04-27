package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.tools.input.BashToolInput;
import io.leavesfly.jharness.tools.shell.ShellSession;
import io.leavesfly.jharness.tools.shell.ShellSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
     *
     * 超时语义修复：旧实现先 while(readLine!=null) 读完所有输出再 waitFor(timeout)，
     * 导致当子进程持续输出但不退出时，readLine 永远阻塞，waitFor 的超时永不生效。
     * 这里改为启动独立的 drain 线程读取输出，主线程 waitFor(timeout) 控制真实超时。
     */
    private ToolResult runOneShot(BashToolInput input, ToolExecutionContext context)
            throws IOException, InterruptedException {
        String command = input.getCommand();
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(context.getCwd().toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        AtomicReference<IOException> readError = new AtomicReference<>();
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    synchronized (output) {
                        output.append(buffer, 0, read);
                        if (output.length() > MAX_OUTPUT_LENGTH * 2) {
                            // 避免超长命令下输出无限增长占内存；真正截断在返回时进行
                            output.setLength(MAX_OUTPUT_LENGTH * 2);
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                readError.set(e);
            }
        }, "jh-bash-drain");
        drainer.setDaemon(true);
        drainer.start();

        boolean finished = process.waitFor(input.getTimeout(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            // 等 drainer 收尾，但不无限等待
            drainer.join(TimeUnit.SECONDS.toMillis(2));
            return ToolResult.error("命令执行超时 (" + input.getTimeout() + "秒)");
        }
        // 进程已退出，等 drainer 把剩余输出收完
        drainer.join(TimeUnit.SECONDS.toMillis(2));
        if (readError.get() != null) {
            logger.warn("读取 bash 输出时出现 IO 异常", readError.get());
        }

        int exitCode = process.exitValue();
        String snapshot;
        synchronized (output) {
            snapshot = output.toString();
        }
        String result = truncateOutput(snapshot);
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
     *
     * 修复点：旧实现直接对原始字符串正则 ">"，会把 `echo "a>b"`、`echo 'a>b'` 中引号里的
     * 字面量误判为重定向，进而把这类只读操作错判为写操作。这里先剥离引号内的字面量字符，
     * 再做重定向检测，避免误报。
     */
    private static boolean containsWriteOperator(String command) {
        String stripped = stripQuoted(command);
        // 检测输出重定向（> 和 >>），排除 >=、=>、<> 等比较/流符号
        if (stripped.matches(".*(?<![=><!])>(?!=).*")) {
            return true;
        }
        // 检测 tee 命令（将输出写入文件）
        if (stripped.matches(".*\\btee\\b.*")) {
            return true;
        }
        return false;
    }

    /**
     * 去除命令中所有引号包裹的字面量（'...' 与 "..."），
     * 同时保留引号位置为占位空格，避免 token 边界改变。
     *
     * 仅用于启发式检测，不涉及命令实际执行，故无需完整 shell 词法分析。
     */
    private static String stripQuoted(String command) {
        StringBuilder sb = new StringBuilder(command.length());
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote == 0) {
                if (c == '\'' || c == '"') {
                    quote = c;
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
            } else {
                if (c == quote) {
                    quote = 0;
                    sb.append(' ');
                } else {
                    sb.append(' '); // 字面量内容一律替换为空格，消除引号内 > 等字符
                }
            }
        }
        return sb.toString();
    }

    /**
     * 检测高危命令模式，返回匹配的模式或 null。
     *
     * 使用预编译 Pattern + find() 提升性能（相比每次 matches() 正则编译）。
     * 同时对命令做最小规范化（去引号、归一 ${IFS}/空白），兜住常见绕过手法。
     *
     * public 暴露：供 CronCreateTool/RemoteTriggerTool 等间接执行 shell 的工具
     * 在入库 / 执行前做统一校验，避免成为黑名单绕过通道。
     */
    public static String detectDangerousCommand(String command) {
        if (command == null) {
            return null;
        }
        String normalized = normalizeForDetection(command);
        for (Pattern pattern : DANGEROUS_COMMAND_PATTERNS) {
            if (pattern.matcher(command).find() || pattern.matcher(normalized).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }

    /**
     * 最小规范化：去除单/双引号、将 ${IFS}/$IFS、制表/换行、连续空白归一为单空格。
     * 仅用于黑名单匹配，不用于实际执行。
     */
    private static String normalizeForDetection(String command) {
        String result = command.replace("'", "").replace("\"", "");
        result = result.replace("${IFS}", " ")
                .replace("$IFS", " ")
                .replace("\t", " ")
                .replace("\n", " ")
                .replace("\r", " ");
        return result.replaceAll("\\s+", " ").trim();
    }
}