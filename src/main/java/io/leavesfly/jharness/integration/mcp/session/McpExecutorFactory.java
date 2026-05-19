package io.leavesfly.jharness.integration.mcp.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 创建 MCP 模块专用的有界线程池。
 *
 * 默认 newCachedThreadPool 在高并发 MCP 场景可能拉爆线程数；这里采用有界线程池 +
 * 命名 ThreadFactory + CallerRunsPolicy，避免线程爆炸，便于 jstack 定位 MCP 线程。
 */
public final class McpExecutorFactory {

    private static final Logger logger = LoggerFactory.getLogger(McpExecutorFactory.class);

    private McpExecutorFactory() {}

    public static ExecutorService newDefault() {
        int core = 2;
        int max = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jharness-mcp-" + counter.incrementAndGet());
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thread, ex) ->
                        logger.error("MCP 线程未捕获异常: {}", thread.getName(), ex));
                return t;
            }
        };
        return new ThreadPoolExecutor(
                core, max,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                tf,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
