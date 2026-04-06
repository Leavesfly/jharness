package io.leavesfly.jharness.tools;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具执行上下文
 *
 * 提供工具执行时需要的上下文信息，如工作目录、元数据等。
 */
public class ToolExecutionContext {
    private final Path cwd;
    private final Map<String, Object> metadata;

    public ToolExecutionContext(Path cwd, Map<String, Object> metadata) {
        this.cwd = cwd;
        this.metadata = metadata != null ? new ConcurrentHashMap<>(metadata) : new ConcurrentHashMap<>();
    }

    public Path getCwd() {
        return cwd;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }

    public void setMetadata(String key, Object value) {
        metadata.put(key, value);
    }
}
