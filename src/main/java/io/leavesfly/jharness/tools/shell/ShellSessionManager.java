package io.leavesfly.jharness.tools.shell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持久 Shell 会话管理器（F-P1-5）。
 *
 * 按 sessionId 维护一组长驻的 {@link ShellSession}，BashTool 会查询此管理器来决定
 * 是在现有会话上执行命令还是新建一个会话。默认策略：
 * - 若传入 sessionId 存在且未关闭 → 复用；
 * - 若传入 sessionId 不存在 → 新建并注册；
 * - 若 sessionId 为空 → 返回 null，调用方走"一次性进程"旧路径。
 *
 * 单例 + ConcurrentHashMap 保证多线程/多工具并发调用安全。
 * 通过 JVM shutdown hook 在进程退出时自动关闭所有会话，防止僵尸 bash。
 */
public class ShellSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(ShellSessionManager.class);

    /** 会话闲置多久后被自动回收（超过则 {@link #reapIdle} 关闭）。 */
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    /** 单进程最多允许同时存在的会话数，超过则拒绝新建。 */
    private static final int MAX_SESSIONS = 16;

    private static final ShellSessionManager INSTANCE = new ShellSessionManager();

    public static ShellSessionManager getInstance() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<String, ShellSession> sessions = new ConcurrentHashMap<>();

    private ShellSessionManager() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeAll, "jh-shell-cleanup"));
    }

    /**
     * 获取或创建指定 sessionId 的会话。
     *
     * @param sessionId 会话标识，非空
     * @param cwd       新建会话时的工作目录
     * @return 可用的 ShellSession
     * @throws IOException 启动底层 bash 进程失败
     * @throws IllegalStateException 超过最大并发会话数
     */
    public ShellSession getOrCreate(String sessionId, Path cwd) throws IOException {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        // 先清理已关闭或闲置过久的会话，避免每次 get 都带一串无效实例
        reapIdle();

        ShellSession existing = sessions.get(sessionId);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        if (existing != null) {
            sessions.remove(sessionId, existing);
        }

        if (sessions.size() >= MAX_SESSIONS) {
            throw new IllegalStateException("shell 会话数量已达上限 " + MAX_SESSIONS
                    + "，请先关闭部分会话");
        }

        ShellSession created = ShellSession.create(sessionId, cwd);
        // putIfAbsent 防止并发创建时丢失刚刚入池的实例
        ShellSession prev = sessions.putIfAbsent(sessionId, created);
        if (prev != null && !prev.isClosed()) {
            // 并发场景下有别的线程先入池，关闭本线程新建的冗余会话
            created.close();
            return prev;
        }
        return created;
    }

    /** 主动关闭指定会话；不存在则返回 false。 */
    public boolean close(String sessionId) {
        ShellSession s = sessions.remove(sessionId);
        if (s == null) return false;
        s.close();
        return true;
    }

    /** 回收所有已关闭或闲置超时的会话。 */
    public int reapIdle() {
        Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
        List<String> toRemove = new ArrayList<>();
        for (var entry : sessions.entrySet()) {
            ShellSession s = entry.getValue();
            if (s.isClosed() || s.getLastUsedAt().isBefore(cutoff)) {
                toRemove.add(entry.getKey());
            }
        }
        for (String k : toRemove) {
            ShellSession s = sessions.remove(k);
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
        if (!toRemove.isEmpty()) {
            logger.debug("已回收 {} 个闲置/关闭的 shell 会话", toRemove.size());
        }
        return toRemove.size();
    }

    /** 用于 /shell_list 命令展示。 */
    public Collection<ShellSession> list() {
        return sessions.values();
    }

    /** JVM 退出时批量清理。 */
    public void closeAll() {
        for (ShellSession s : sessions.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        sessions.clear();
    }
}
