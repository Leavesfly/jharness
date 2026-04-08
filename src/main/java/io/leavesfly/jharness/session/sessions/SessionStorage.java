package io.leavesfly.jharness.session.sessions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 会话存储
 *
 * 管理会话快照的保存和加载。
 */
public class SessionStorage {
    private static final Logger logger = LoggerFactory.getLogger(SessionStorage.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path sessionsDir;
    private final Map<String, Object> saveLocks = new ConcurrentHashMap<>();

    public SessionStorage(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            logger.error("创建会话目录失败", e);
        }
    }

    /**
     * 保存会话快照
     *
     * 使用 per-session 锁保证同一会话的并发写入安全。
     * 锁对象在写入完成后立即移除，防止 saveLocks 无限增长导致内存泄漏。
     */
    public void saveSession(SessionSnapshot snapshot) {
        String sessionId = snapshot.getSessionId();
        Object lock = saveLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            try {
                Path file = sessionsDir.resolve("session-" + sessionId + ".json");
                // 先写入临时文件，然后原子性移动，防止写入中断导致数据损坏
                Path tempFile = sessionsDir.resolve("session-" + sessionId + ".tmp");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), snapshot);
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                logger.debug("会话已保存: {}", sessionId);
            } catch (IOException e) {
                logger.error("保存会话失败", e);
            } finally {
                // 写入完成后移除锁对象，防止 saveLocks 无限增长
                saveLocks.remove(sessionId, lock);
            }
        }
    }

    /**
     * 加载会话快照
     *
     * @return 会话快照，如果不存在或加载失败返回 null
     */
    public SessionSnapshot loadSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            logger.warn("会话 ID 不能为空");
            return null;
        }
        // 安全检查：防止路径遍历
        if (sessionId.contains("/") || sessionId.contains("\\") || sessionId.contains("..")) {
            logger.warn("无效的会话 ID: {}", sessionId);
            return null;
        }
        try {
            Path file = sessionsDir.resolve("session-" + sessionId + ".json").normalize();
            // 确保解析后的路径仍在 sessionsDir 内
            if (!file.startsWith(sessionsDir.normalize())) {
                logger.warn("会话 ID 包含非法路径遍历: {}", sessionId);
                return null;
            }
            if (!Files.exists(file)) return null;
            return MAPPER.readValue(file.toFile(), SessionSnapshot.class);
        } catch (IOException e) {
            logger.error("加载会话失败: sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 列出最近的会话
     */
    public List<SessionSnapshot> listSessions(int limit) {
        try (Stream<Path> files = Files.list(sessionsDir)) {
            return files
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            return MAPPER.readValue(p.toFile(), SessionSnapshot.class);
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(s -> s != null)
                    .sorted(Comparator.comparing(SessionSnapshot::getCreatedAt).reversed())
                    .limit(limit)
                    .toList();
        } catch (IOException e) {
            logger.error("列出会话失败", e);
            return new ArrayList<>();
        }
    }
}