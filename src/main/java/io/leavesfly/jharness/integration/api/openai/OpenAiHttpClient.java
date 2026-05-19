package io.leavesfly.jharness.integration.api.openai;

import io.leavesfly.jharness.integration.api.ApiMessageCompleteEvent;
import okhttp3.OkHttpClient;
import okhttp3.sse.EventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 负责 OpenAI HTTP 连接生命周期管理：
 *   - 构造 OkHttpClient（连接/读/写超时可配置）；
 *   - 跟踪所有活跃 SSE EventSource 与未完成的 future；
 *   - 关闭/取消时确保不泄漏连接池与 dispatcher 线程，且让等待中的 future 以
 *     CancellationException 完成，避免 join() 永久阻塞。
 */
public final class OpenAiHttpClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiHttpClient.class);

    private final OkHttpClient okHttp;
    private final List<EventSource> activeEventSources = Collections.synchronizedList(new ArrayList<>());
    private final List<CompletableFuture<ApiMessageCompleteEvent>> activeFutures =
            Collections.synchronizedList(new ArrayList<>());

    public OpenAiHttpClient(int connectTimeoutSeconds, int readTimeoutSeconds, int writeTimeoutSeconds) {
        if (connectTimeoutSeconds <= 0 || readTimeoutSeconds <= 0 || writeTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "超时参数必须为正数: connect=" + connectTimeoutSeconds
                            + ", read=" + readTimeoutSeconds
                            + ", write=" + writeTimeoutSeconds);
        }
        this.okHttp = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    public OkHttpClient okHttp() {
        return okHttp;
    }

    public void registerEventSource(EventSource es) {
        activeEventSources.add(es);
    }

    public void unregisterEventSource(EventSource es) {
        activeEventSources.remove(es);
    }

    public void registerFuture(CompletableFuture<ApiMessageCompleteEvent> future) {
        activeFutures.add(future);
        future.whenComplete((ok, err) -> activeFutures.remove(future));
    }

    /**
     * 取消所有活跃的 SSE 与等待中的 future。
     *
     * EventSource.cancel() 并不保证后续触发 onClosed/onFailure 回调，因此必须**主动**
     * 把所有未完成 future 以 cancel(true) 结束，否则上层 join() 会永久阻塞。
     */
    public void cancelAll() {
        synchronized (activeEventSources) {
            for (EventSource es : activeEventSources) {
                try {
                    es.cancel();
                } catch (Exception e) {
                    logger.warn("取消 SSE 连接失败", e);
                }
            }
            activeEventSources.clear();
        }
        synchronized (activeFutures) {
            for (CompletableFuture<ApiMessageCompleteEvent> f : activeFutures) {
                if (!f.isDone()) {
                    f.cancel(true);
                }
            }
            activeFutures.clear();
        }
        logger.info("已取消所有活跃的 LLM 请求");
    }

    /**
     * 关闭客户端：每个关闭步骤独立 try/catch，任何一步失败都不影响后续资源释放。
     */
    @Override
    public void close() {
        // 1. 取消所有活跃 SSE
        try {
            synchronized (activeEventSources) {
                for (EventSource es : activeEventSources) {
                    try { es.cancel(); } catch (Exception e) { logger.warn("取消 SSE 连接失败", e); }
                }
                activeEventSources.clear();
            }
        } catch (Exception e) {
            logger.warn("清理活跃 SSE 列表失败", e);
        }

        // 2. dispatcher 线程池：shutdown → 短暂等待 → shutdownNow 三段式
        try {
            ExecutorService executor = okHttp.dispatcher().executorService();
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("HTTP dispatcher 未在 5s 内关闭，执行强制关闭");
                executor.shutdownNow();
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    logger.warn("HTTP dispatcher 强制关闭仍未完成，放弃等待");
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("等待 HTTP dispatcher 关闭时被中断");
            okHttp.dispatcher().executorService().shutdownNow();
        } catch (Exception e) {
            logger.warn("关闭 HTTP dispatcher 失败", e);
        }

        // 3. 连接池
        try {
            okHttp.connectionPool().evictAll();
        } catch (Exception e) {
            logger.warn("清理 HTTP 连接池失败", e);
        }

        // 4. 可选缓存
        if (okHttp.cache() != null) {
            try { okHttp.cache().close(); } catch (Exception e) { logger.warn("关闭 HTTP 缓存失败", e); }
        }
    }
}
