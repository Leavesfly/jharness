package io.leavesfly.jharness.extension.plugins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件目录路径工具类。
 *
 * 【P1】除主目录外，还提供 {@code ~/.claude/plugins} 与 {@code <cwd>/.claude/plugins}
 * 作为 Claude Code 生态兼容的回退扫描目录，方便用户复用已有插件。
 */
public class PluginPaths {

    /**
     * 获取用户插件主目录 (~/.jharness/plugins/)；会尝试创建目录。
     */
    public static Path getUserPluginsDir() {
        Path pluginsDir = Path.of(System.getProperty("user.home"), ".jharness", "plugins");
        try {
            Files.createDirectories(pluginsDir);
        } catch (Exception e) {
            // 忽略创建失败
        }
        return pluginsDir;
    }

    /**
     * 获取项目插件主目录 (&lt;cwd&gt;/.jharness/plugins/)
     */
    public static Path getProjectPluginsDir(Path cwd) {
        if (cwd == null) {
            return null;
        }
        return cwd.resolve(".jharness").resolve("plugins");
    }

    /**
     * 【P1】用户级 Claude Code 兼容目录 (~/.claude/plugins/)。
     * 仅作为回退扫描，不会主动创建，避免污染用户 Claude Code 的目录。
     */
    public static Path getUserClaudeCompatDir() {
        return Path.of(System.getProperty("user.home"), ".claude", "plugins");
    }

    /**
     * 【P1】项目级 Claude Code 兼容目录 (&lt;cwd&gt;/.claude/plugins/)。
     */
    public static Path getProjectClaudeCompatDir(Path cwd) {
        if (cwd == null) {
            return null;
        }
        return cwd.resolve(".claude").resolve("plugins");
    }

    /**
     * 【P1】列出所有"候选用户级插件根目录"，按优先级排序（主目录优先）。
     * 只返回实际存在的目录。
     */
    public static List<Path> listUserPluginRoots() {
        List<Path> roots = new ArrayList<>();
        Path primary = getUserPluginsDir();
        if (primary != null && Files.isDirectory(primary)) {
            roots.add(primary);
        }
        Path compat = getUserClaudeCompatDir();
        if (compat != null && Files.isDirectory(compat)) {
            roots.add(compat);
        }
        return roots;
    }

    /**
     * 【P1】列出所有"候选项目级插件根目录"，按优先级排序（主目录优先）。
     */
    public static List<Path> listProjectPluginRoots(Path cwd) {
        List<Path> roots = new ArrayList<>();
        if (cwd == null) return roots;
        Path primary = getProjectPluginsDir(cwd);
        if (primary != null && Files.isDirectory(primary)) {
            roots.add(primary);
        }
        Path compat = getProjectClaudeCompatDir(cwd);
        if (compat != null && Files.isDirectory(compat)) {
            roots.add(compat);
        }
        return roots;
    }
}
