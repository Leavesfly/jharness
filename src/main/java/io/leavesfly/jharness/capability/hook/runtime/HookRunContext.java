package io.leavesfly.jharness.capability.hook.runtime;

import io.leavesfly.jharness.capability.hook.HookEvent;
import io.leavesfly.jharness.capability.permission.PermissionChecker;

import java.nio.file.Path;
import java.util.Map;

/**
 * 单次 Hook 执行的不可变上下文。
 *
 * 由 {@link io.leavesfly.jharness.capability.hook.HookExecutor} 在 execute 入口构建，
 * 传递给所有 {@link HookRunner} 实现使用，避免每个 Runner 单独持有 cwd/checker/depth。
 */
public final class HookRunContext {

    private final HookEvent event;
    private final Map<String, Object> payload;
    private final Path cwd;
    private final PermissionChecker permissionChecker;
    private final int currentDepth;

    public HookRunContext(HookEvent event, Map<String, Object> payload,
                          Path cwd, PermissionChecker permissionChecker, int currentDepth) {
        this.event = event;
        this.payload = payload;
        this.cwd = cwd;
        this.permissionChecker = permissionChecker;
        this.currentDepth = currentDepth;
    }

    public HookEvent getEvent() { return event; }
    public Map<String, Object> getPayload() { return payload; }
    public Path getCwd() { return cwd; }
    public PermissionChecker getPermissionChecker() { return permissionChecker; }
    public int getCurrentDepth() { return currentDepth; }
}
