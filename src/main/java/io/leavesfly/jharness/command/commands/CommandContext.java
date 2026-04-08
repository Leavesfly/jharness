package io.leavesfly.jharness.command.commands;

import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.core.engine.QueryEngine;
import io.leavesfly.jharness.session.permissions.PermissionChecker;
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
    private final PermissionChecker permissionChecker;
    private final Settings settings;

    public CommandContext(Path cwd, QueryEngine engine, ToolRegistry toolRegistry,
                          PermissionChecker permissionChecker, Settings settings) {
        this.cwd = cwd;
        this.engine = engine;
        this.toolRegistry = toolRegistry;
        this.permissionChecker = permissionChecker;
        this.settings = settings;
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

    public PermissionChecker getPermissionChecker() {
        return permissionChecker;
    }

    public Settings getSettings() {
        return settings;
    }
}
