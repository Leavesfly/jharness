package io.leavesfly.jharness.extension.plugins;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 插件目录路径工具类
 */
public class PluginPaths {
    
    /**
     * 获取用户插件目录 (~/.jharness/plugins/)
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
     * 获取项目插件目录 (<cwd>/.jharness/plugins/)
     */
    public static Path getProjectPluginsDir(Path cwd) {
        if (cwd == null) {
            return null;
        }
        return cwd.resolve(".jharness").resolve("plugins");
    }
}
