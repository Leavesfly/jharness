package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.session.permissions.PermissionChecker;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具执行上下文
 *
 * 提供工具执行时需要的上下文信息，如工作目录、元数据、运行中的 PermissionChecker 等。
 *
 * PermissionChecker 作为一等字段注入到执行上下文，允许 EnterPlanModeTool 等模式切换工具
 * 在执行期读取/调整运行时权限状态。保留两参构造器以向后兼容，此时 permissionChecker 为 null，
 * 调用方需自行判空降级。
 */
public class ToolExecutionContext {
    private final Path cwd;
    private final Map<String, Object> metadata;
    private final PermissionChecker permissionChecker;

    public ToolExecutionContext(Path cwd, Map<String, Object> metadata) {
        this(cwd, metadata, null);
    }

    public ToolExecutionContext(Path cwd, Map<String, Object> metadata,
                                PermissionChecker permissionChecker) {
        this.cwd = cwd;
        this.metadata = metadata != null ? new ConcurrentHashMap<>(metadata) : new ConcurrentHashMap<>();
        this.permissionChecker = permissionChecker;
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

    /**
     * 获取运行时 PermissionChecker。可能为 null（旧调用方未注入时），使用前需判空。
     */
    public PermissionChecker getPermissionChecker() {
        return permissionChecker;
    }
}
