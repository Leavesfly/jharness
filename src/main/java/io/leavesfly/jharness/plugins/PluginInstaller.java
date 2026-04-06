package io.leavesfly.jharness.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;

/**
 * 插件安装器
 */
public class PluginInstaller {
    private static final Logger logger = LoggerFactory.getLogger(PluginInstaller.class);

    /**
     * 安装插件
     */
    public static boolean installPlugin(Path sourceDir) throws IOException {
        if (!Files.exists(sourceDir) || !Files.isDirectory(sourceDir)) {
            throw new IllegalArgumentException("Source directory does not exist: " + sourceDir);
        }

        // 安全检查：规范化路径，防止路径遍历
        Path normalizedSource = sourceDir.toAbsolutePath().normalize();

        // 读取清单获取插件名称
        PluginManifest manifest = PluginLoader.findManifest(normalizedSource);
        if (manifest == null) {
            throw new IllegalArgumentException("No plugin.json found in: " + normalizedSource);
        }

        // 安全检查：验证插件名称不包含路径分隔符
        String pluginName = manifest.getName();
        if (pluginName == null || pluginName.contains("/") || pluginName.contains("\\")
                || pluginName.contains("..") || pluginName.isBlank()) {
            throw new IllegalArgumentException("Invalid plugin name: " + pluginName);
        }

        Path userPluginsDir = PluginPaths.getUserPluginsDir();
        Path targetDir = userPluginsDir.resolve(pluginName).normalize();

        // 安全检查：确保目标路径在插件目录内
        if (!targetDir.startsWith(userPluginsDir.toAbsolutePath().normalize())) {
            throw new SecurityException("Path traversal detected in plugin name: " + pluginName);
        }

        if (Files.exists(targetDir)) {
            // 如果已存在，先删除
            deleteDirectory(targetDir);
        }

        // 复制目录
        Files.createDirectories(targetDir);
        copyDirectory(sourceDir, targetDir);

        logger.info("插件已安装: {} -> {}", manifest.getName(), targetDir);
        return true;
    }

    /**
     * 卸载插件
     */
    public static boolean uninstallPlugin(String name) throws IOException {
        // 安全检查：验证插件名称
        if (name == null || name.contains("/") || name.contains("\\")
                || name.contains("..") || name.isBlank()) {
            throw new IllegalArgumentException("Invalid plugin name: " + name);
        }

        Path userPluginsDir = PluginPaths.getUserPluginsDir();
        Path pluginDir = userPluginsDir.resolve(name).normalize();

        // 安全检查：确保路径在插件目录内
        if (!pluginDir.startsWith(userPluginsDir.toAbsolutePath().normalize())) {
            throw new SecurityException("Path traversal detected in plugin name: " + name);
        }

        if (!Files.exists(pluginDir)) {
            logger.warn("插件不存在: {}", name);
            return false;
        }

        deleteDirectory(pluginDir);
        logger.info("插件已卸载: {}", name);
        return true;
    }

    /**
     * 递归复制目录
     */
    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 递归删除目录
     */
    private static void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.compareTo(a)) // 反向排序：先文件后目录
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                logger.error("删除失败: {}", path, e);
                            }
                        });
            }
        }
    }
}
