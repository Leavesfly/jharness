package io.leavesfly.jharness.capability.hook.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 子进程 IO 公共逻辑：限量读取输出 + 优雅销毁。
 *
 * 抽离自原 HookExecutor 中 Command/Prompt/Agent Hook 都用到的同一段代码。
 */
public final class SubprocessIo {

    private SubprocessIo() {}

    /** 读取结果，包含字符串与是否被截断的标记。 */
    public static final class CapturedOutput {
        public final String text;
        public final boolean truncated;

        public CapturedOutput(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }

    /**
     * 限量读取子进程合并输出（要求 ProcessBuilder.redirectErrorStream(true)）。
     * 超过 {@code limitBytes} 后继续读但丢弃，避免子进程因管道阻塞无法退出。
     */
    public static CapturedOutput readLimited(Process process, long limitBytes) throws IOException {
        StringBuilder output = new StringBuilder();
        long totalBytes = 0L;
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                if (totalBytes + n > limitBytes) {
                    int remain = (int) (limitBytes - totalBytes);
                    if (remain > 0) {
                        output.append(buf, 0, remain);
                        totalBytes += remain;
                    }
                    truncated = true;
                    //noinspection StatementWithEmptyBody
                    while (reader.read(buf) != -1) { /* drain */ }
                    break;
                }
                output.append(buf, 0, n);
                totalBytes += n;
            }
        }
        return new CapturedOutput(output.toString(), truncated);
    }

    /**
     * 优雅销毁进程：先 destroy（SIGTERM），3s 无响应则 destroyForcibly（SIGKILL）。
     */
    public static void destroyGracefully(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
