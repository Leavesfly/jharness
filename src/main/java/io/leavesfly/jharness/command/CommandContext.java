package io.leavesfly.jharness.command;

import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.kernel.spi.PermissionGate;
import io.leavesfly.jharness.kernel.state.AppStateStore;
import io.leavesfly.jharness.tools.ToolRegistry;

import java.nio.file.Path;

/**
 * 命令上下文
 *
 * 提供命令执行时需要的上下文信息。
 */
public class CommandContext {
    private final Path cwd;
    private final QueryEngine engine;
    private final ToolRegistry toolRegistry;
    private final PermissionGate permissionChecker;
    private final Settings settings;
    private final AppStateStore appStateStore;

    public CommandContext(Path cwd, QueryEngine engine, ToolRegistry toolRegistry,
                          PermissionGate permissionChecker, Settings settings) {
        this(cwd, engine, toolRegistry, permissionChecker, settings, null);
    }

    public CommandContext(Path cwd, QueryEngine engine, ToolRegistry toolRegistry,
                          PermissionGate permissionChecker, Settings settings,
                          AppStateStore appStateStore) {
        this.cwd = cwd;
        this.engine = engine;
        this.toolRegistry = toolRegistry;
        this.permissionChecker = permissionChecker;
        this.settings = settings;
        this.appStateStore = appStateStore;
    }

    public Path getCwd() {
        return cwd;
    }

    public QueryEngine getEngine() {
        return engine;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public PermissionGate getPermissionChecker() {
        return permissionChecker;
    }

    public Settings getSettings() {
        return settings;
    }

    public AppStateStore getAppStateStore() {
        return appStateStore;
    }
}
