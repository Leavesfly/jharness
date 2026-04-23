package io.leavesfly.jharness.tools.shell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 持久化的 Shell 会话（F-P1-5）。
 *
 * 通过启动一个长驻的 {@code bash} 进程，让同一 session 内的多条命令共享环境变量、
 * 工作目录、shell 别名/函数等上下文，避免每次命令都重开进程导致状态丢失。
 *
 * 实现要点：
 * - 使用哨兵机制分隔每条命令的输出：在用户命令后追加 {@code echo __JH_MARKER_<seq>__}，
 *   通过扫描 marker 行判定命令结束，同一行前面的即为本次输出；
 * - 退出码通过 {@code __EXIT_<seq>=$?} 前缀行捕获；
 * - 单个 session 使用 {@link ReentrantLock} 保证同一时刻只有一条命令在执行，
 *   避免多线程并发写入 stdin 造成输出错乱；
 * - 超时到期后强杀进程并标记 {@link #closed}，后续调用会重新由 manager 拉起新进程。
 */
public class ShellSession implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ShellSession.class);

    private final String sessionId;
    private final Process process;
    private final Writer stdin;
    private final BufferedReader stdout;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong sequence = new AtomicLong();
    private final Instant createdAt = Instant.now();
    private volatile Instant lastUsedAt = Instant.now();
    private volatile boolean closed;

    public ShellSession(String sessionId, Path cwd) throws IOException {
        this.sessionId = sessionId;

        ProcessBuilder pb = new ProcessBuilder("bash", "--noprofile", "--norc", "-i");
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        // 禁用交互式提示，避免读取 marker 时混入 PS1
        pb.environment().put("PS1", "");
        pb.environment().put("PROMPT_COMMAND", "");
        this.process = pb.start();
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        this.stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        logger.info("已启动 shell 会话 id={} pid={}", sessionId, process.pid());
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public boolean isClosed() {
        return closed || !process.isAlive();
    }

    /**
     * 在本会话中执行一条命令，返回输出和退出码。
     *
     * @param command       用户命令
     * @param timeoutSeconds 单次命令超时秒数
     * @return 执行结果
     * @throws IOException          读写底层进程失败
     * @throws InterruptedException 等待输出被中断
     */
    public Result run(String command, int timeoutSeconds) throws IOException, InterruptedException {
        if (isClosed()) {
            throw new IOException("shell 会话已关闭: " + sessionId);
        }
        lock.lock();
        try {
            long seq = sequence.incrementAndGet();
            String startMarker = String.format("__JH_START_%d__", seq);
            String endMarker = String.format("__JH_END_%d__", seq);
            String exitPrefix = String.format("__JH_EXIT_%d__=", seq);

            // 先输出起始 marker，再执行命令，再输出退出码 + 结束 marker
            // 起始 marker 用于跳过之前残留的输出（如上次命令的遗留行）。
            String wrapped = "echo " + startMarker + "\n"
                    + command + "\n"
                    + "echo \"" + exitPrefix + "$?\"\n"
                    + "echo " + endMarker + "\n";
            stdin.write(wrapped);
            stdin.flush();

            // 读取输出直到遇到 endMarker 或超时
            StringBuilder out = new StringBuilder();
            int exitCode = -1;
            boolean started = false;
            boolean timedOut = false;
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

            while (true) {
                if (System.nanoTime() > deadlineNanos) {
                    timedOut = true;
                    break;
                }
                // 非阻塞：stdout.ready() 检查后再 readLine；若没数据就小睡让位 CPU
                if (!stdout.ready()) {
                    if (!process.isAlive()) {
                        break;
                    }
                    Thread.sleep(20);
                    continue;
                }
                String line = stdout.readLine();
                if (line == null) {
                    break;
                }
                if (!started) {
                    if (line.trim().equals(startMarker)) {
                        started = true;
                    }
                    continue;
                }
                if (line.startsWith(exitPrefix)) {
                    try {
                        exitCode = Integer.parseInt(line.substring(exitPrefix.length()).trim());
                    } catch (NumberFormatException nfe) {
                        exitCode = -1;
                    }
                    continue;
                }
                if (line.trim().equals(endMarker)) {
                    break;
                }
                out.append(line).append('\n');
            }

            lastUsedAt = Instant.now();

            if (timedOut) {
                // 超时后会话状态不可预测（命令可能仍在运行），强制关闭以避免污染后续命令
                logger.warn("shell 会话 {} 命令超时，关闭会话: {}", sessionId, command);
                close();
                return new Result(out.toString(), -1, true);
            }
            return new Result(out.toString(), exitCode, false);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            stdin.write("exit\n");
            stdin.flush();
        } catch (IOException ignored) {
            // 忽略，强杀兜底
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try { stdin.close(); } catch (IOException ignored) {}
        try { stdout.close(); } catch (IOException ignored) {}
        logger.info("已关闭 shell 会话 id={}", sessionId);
    }

    /**
     * 命令执行结果。
     */
    public record Result(String output, int exitCode, boolean timedOut) {}

    /** 仅用于工厂方法：创建指向给定目录的会话。 */
    static ShellSession create(String sessionId, Path cwd) throws IOException {
        if (!Files.isDirectory(cwd)) {
            throw new IOException("工作目录不存在: " + cwd);
        }
        return new ShellSession(sessionId, cwd);
    }
}
