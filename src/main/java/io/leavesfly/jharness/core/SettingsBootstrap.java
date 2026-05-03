package io.leavesfly.jharness.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 配置与默认插件首启种子逻辑。
 *
 * <p>职责：当用户首次启动 JHarness 且 {@code ~/.jharness/settings.json} 不存在时，
 * 从 classpath 资源 {@code defaults/} 下释放一份默认配置与默认插件到用户主目录，使项目开箱即用：
 * <ul>
 *   <li>{@code defaults/settings.json} → {@code ~/.jharness/settings.json}</li>
 *   <li>{@code defaults/plugins/builtin-defaults/} → {@code ~/.jharness/plugins/builtin-defaults/}</li>
 * </ul>
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>幂等</b>：仅在目标文件/目录不存在时拷贝，不会覆盖用户本地的修改；</li>
 *   <li><b>失败不阻断</b>：任何 IO 异常都降级为警告日志，不影响主流程（用户仍可通过环境变量运行）；</li>
 *   <li><b>兼容 JAR 与 IDE 运行</b>：同时支持 classpath 来源为 {@code file:} 目录和 {@code jar:} 归档。</li>
 * </ol>
 */
public final class SettingsBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(SettingsBootstrap.class);

    /** classpath 根下的默认资源前缀。 */
    static final String DEFAULTS_ROOT = "defaults";
    static final String DEFAULT_SETTINGS_RESOURCE = DEFAULTS_ROOT + "/settings.json";
    static final String DEFAULT_PLUGINS_RESOURCE_DIR = DEFAULTS_ROOT + "/plugins";

    private SettingsBootstrap() {}

    /**
     * 使用默认的用户目录 {@code ~/.jharness/} 执行 seed。
     */
    public static void seedIfAbsent() {
        seedIfAbsent(Settings.getDefaultConfigDir());
    }

    /**
     * 将 classpath 下的默认资源 seed 到指定的配置根目录。
     *
     * <p>注意：此方法被设计为对测试友好，允许传入临时目录。
     *
     * @param configDir 目标配置根目录（通常是 {@code ~/.jharness/}）
     */
    public static void seedIfAbsent(Path configDir) {
        if (configDir == null) {
            return;
        }
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            logger.warn("创建配置目录失败，跳过 seed: {} ({})", configDir, e.getMessage());
            return;
        }

        seedSettingsFile(configDir);
        seedDefaultPlugins(configDir);
    }

    /**
     * Seed {@code settings.json}：仅在文件不存在时从 classpath 拷贝。
     */
    private static void seedSettingsFile(Path configDir) {
        Path target = configDir.resolve("settings.json");
        if (Files.exists(target)) {
            return;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = SettingsBootstrap.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(DEFAULT_SETTINGS_RESOURCE)) {
            if (in == null) {
                logger.warn("未找到默认配置资源: {}", DEFAULT_SETTINGS_RESOURCE);
                return;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            logger.info("已写入默认配置: {}", target);
        } catch (IOException e) {
            logger.warn("写入默认配置失败: {} ({})", target, e.getMessage());
        }
    }

    /**
     * Seed 默认插件目录：遍历 classpath 下 {@link #DEFAULT_PLUGINS_RESOURCE_DIR} 的每个子目录，
     * 若目标 {@code ~/.jharness/plugins/<plugin>} 不存在，则整目录拷贝过去。
     */
    private static void seedDefaultPlugins(Path configDir) {
        Path pluginsDir = configDir.resolve("plugins");
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            logger.warn("创建插件目录失败，跳过插件 seed: {} ({})", pluginsDir, e.getMessage());
            return;
        }

        List<String> pluginNames = listDefaultPluginNames();
        for (String pluginName : pluginNames) {
            Path pluginTarget = pluginsDir.resolve(pluginName);
            if (Files.exists(pluginTarget)) {
                continue;
            }
            copyClasspathDirectory(DEFAULT_PLUGINS_RESOURCE_DIR + "/" + pluginName, pluginTarget);
            logger.info("已写入默认插件: {}", pluginTarget);
        }
    }

    /**
     * 列出 classpath 下 {@code defaults/plugins} 的一级子目录名。
     * 兼容 {@code file:} 与 {@code jar:} 协议两种 classpath 形态。
     */
    static List<String> listDefaultPluginNames() {
        List<String> names = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = SettingsBootstrap.class.getClassLoader();
        URL url = cl.getResource(DEFAULT_PLUGINS_RESOURCE_DIR);
        if (url == null) {
            return names;
        }
        String protocol = url.getProtocol();
        try {
            if ("file".equals(protocol)) {
                Path dir = Path.of(url.toURI());
                if (Files.isDirectory(dir)) {
                    try (var stream = Files.list(dir)) {
                        stream.filter(Files::isDirectory)
                                .forEach(p -> names.add(p.getFileName().toString()));
                    }
                }
            } else if ("jar".equals(protocol)) {
                URLConnection conn = url.openConnection();
                if (conn instanceof JarURLConnection jarConn) {
                    try (JarFile jar = jarConn.getJarFile()) {
                        String prefix = DEFAULT_PLUGINS_RESOURCE_DIR + "/";
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String entryName = entry.getName();
                            if (!entryName.startsWith(prefix) || entryName.equals(prefix)) continue;
                            String tail = entryName.substring(prefix.length());
                            int slash = tail.indexOf('/');
                            if (slash <= 0) continue;
                            String first = tail.substring(0, slash);
                            if (!names.contains(first)) names.add(first);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("扫描默认插件目录失败: {} ({})", url, e.getMessage());
        }
        Collections.sort(names);
        return names;
    }

    /**
     * 将 classpath 下的某个子目录递归拷贝到目标路径。
     * 兼容 {@code file:} 与 {@code jar:} 协议。
     */
    static void copyClasspathDirectory(String resourceDir, Path targetDir) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = SettingsBootstrap.class.getClassLoader();
        URL url = cl.getResource(resourceDir);
        if (url == null) {
            logger.warn("classpath 资源不存在: {}", resourceDir);
            return;
        }
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            logger.warn("创建目标目录失败: {} ({})", targetDir, e.getMessage());
            return;
        }
        String protocol = url.getProtocol();
        try {
            if ("file".equals(protocol)) {
                Path src = Path.of(url.toURI());
                copyFileTree(src, targetDir);
            } else if ("jar".equals(protocol)) {
                URLConnection conn = url.openConnection();
                if (conn instanceof JarURLConnection jarConn) {
                    try (JarFile jar = jarConn.getJarFile()) {
                        String prefix = resourceDir.endsWith("/") ? resourceDir : resourceDir + "/";
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String entryName = entry.getName();
                            if (!entryName.startsWith(prefix)) continue;
                            String relative = entryName.substring(prefix.length());
                            if (relative.isEmpty()) continue;
                            Path outPath = targetDir.resolve(relative);
                            if (entry.isDirectory()) {
                                Files.createDirectories(outPath);
                            } else {
                                Files.createDirectories(outPath.getParent());
                                try (InputStream in = jar.getInputStream(entry)) {
                                    Files.copy(in, outPath, StandardCopyOption.REPLACE_EXISTING);
                                }
                            }
                        }
                    }
                }
            } else {
                logger.warn("暂不支持的 classpath 协议: {}", protocol);
            }
        } catch (Exception e) {
            logger.warn("拷贝 classpath 目录失败: {} -> {} ({})", resourceDir, targetDir, e.getMessage());
        }
    }

    /** 在文件系统上递归拷贝（IDE/测试模式下常用）。 */
    private static void copyFileTree(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            stream.forEach(source -> {
                try {
                    Path rel = src.relativize(source);
                    Path dest = dst.resolve(rel.toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
