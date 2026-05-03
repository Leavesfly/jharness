package io.leavesfly.jharness.extension.plugins.trust;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.util.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 【P3】插件 author 信任链。
 *
 * <p>落盘位置：{@code ~/.jharness/trusted_authors.json}，格式为 JSON 数组：
 * <pre>{@code
 *   ["alice", "bob", "anthropic"]
 * }</pre>
 *
 * <p>安装新插件时：若 manifest.author 不在信任列表，需要由调用方判断（交互模式下询问用户，
 * 非交互/批量脚本模式可通过 {@link TrustPolicy} 选择拒绝或警告）。
 *
 * <p>线程安全：所有状态变更操作加内部锁；查询走读锁并立即返回快照。
 */
public class TrustStore {

    private static final Logger logger = LoggerFactory.getLogger(TrustStore.class);
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;

    /** 默认持久化文件名。 */
    public static final String STATE_FILE_NAME = "trusted_authors.json";

    private final Path stateFile;
    private final Set<String> trusted = new LinkedHashSet<>();
    private final Object lock = new Object();

    public TrustStore() {
        this(defaultStateFile());
    }

    public TrustStore(Path stateFile) {
        this.stateFile = stateFile;
        load();
    }

    /** 默认 state 文件：{@code ~/.jharness/trusted_authors.json}。 */
    public static Path defaultStateFile() {
        return Path.of(System.getProperty("user.home"), ".jharness", STATE_FILE_NAME);
    }

    private void load() {
        if (stateFile == null || !Files.exists(stateFile)) return;
        try {
            java.util.List<String> list = MAPPER.readValue(
                    Files.readAllBytes(stateFile),
                    new TypeReference<java.util.List<String>>() {});
            synchronized (lock) {
                trusted.clear();
                for (String s : list) {
                    if (s != null && !s.isBlank()) trusted.add(s.trim());
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("读取信任列表失败，忽略: {}", stateFile, e);
        }
    }

    private void persist() {
        if (stateFile == null) return;
        try {
            Files.createDirectories(stateFile.getParent());
            synchronized (lock) {
                Files.writeString(stateFile,
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(trusted));
            }
        } catch (IOException e) {
            logger.warn("持久化信任列表失败: {}", stateFile, e);
        }
    }

    /** 添加一个 author 到信任列表，幂等。 */
    public boolean trust(String author) {
        if (author == null || author.isBlank()) return false;
        synchronized (lock) {
            boolean added = trusted.add(author.trim());
            if (added) persist();
            return added;
        }
    }

    /** 从信任列表移除。 */
    public boolean revoke(String author) {
        if (author == null) return false;
        synchronized (lock) {
            boolean removed = trusted.remove(author.trim());
            if (removed) persist();
            return removed;
        }
    }

    public boolean isTrusted(String author) {
        if (author == null || author.isBlank()) return false;
        synchronized (lock) {
            return trusted.contains(author.trim());
        }
    }

    /** 返回只读快照。 */
    public Set<String> list() {
        synchronized (lock) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(trusted));
        }
    }

    /**
     * 信任策略枚举：在非交互式场景由调用方指定。
     */
    public enum TrustPolicy {
        /** 严格模式：未信任 author 直接拒绝。 */
        STRICT,
        /** 警告模式：未信任 author 记录警告但允许通过。 */
        WARN,
        /** 自动信任模式：遇到未知 author 自动加入信任列表（仅用于脚本化批量安装）。 */
        AUTO
    }

    /**
     * 在 install 链路上做信任评估。
     *
     * @param author 插件 manifest 的 author 字段（null / 空视为"未知 author"）
     * @param policy 策略
     * @return true 表示允许继续安装；false 表示拒绝
     */
    public boolean evaluate(String author, TrustPolicy policy) {
        if (author == null || author.isBlank()) {
            switch (policy == null ? TrustPolicy.WARN : policy) {
                case STRICT:
                    logger.warn("插件缺少 author 字段，严格模式下拒绝安装");
                    return false;
                case AUTO:
                case WARN:
                default:
                    logger.warn("插件缺少 author 字段，按策略 {} 放行", policy);
                    return true;
            }
        }
        if (isTrusted(author)) return true;
        switch (policy == null ? TrustPolicy.WARN : policy) {
            case STRICT:
                logger.warn("author {} 不在信任列表，严格模式下拒绝", author);
                return false;
            case AUTO:
                trust(author);
                logger.info("自动信任 author: {}", author);
                return true;
            case WARN:
            default:
                logger.warn("author {} 不在信任列表，已放行（WARN 策略）", author);
                return true;
        }
    }
}
