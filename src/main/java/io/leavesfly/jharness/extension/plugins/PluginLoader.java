package io.leavesfly.jharness.extension.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.extension.skills.SkillDefinition;
import io.leavesfly.jharness.extension.skills.SkillLoader;
import io.leavesfly.jharness.util.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 插件加载器
 * 
 * 从用户和项目目录发现并加载插件。
 */
public class PluginLoader {
    private static final Logger logger = LoggerFactory.getLogger(PluginLoader.class);
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;

    /**
     * 发现所有插件路径
     */
    public static List<Path> discoverPluginPaths(Path cwd) {
        List<Path> paths = new ArrayList<>();

        // 用户插件目录
        Path userPluginsDir = PluginPaths.getUserPluginsDir();
        if (Files.exists(userPluginsDir) && Files.isDirectory(userPluginsDir)) {
            try (Stream<Path> stream = Files.list(userPluginsDir)) {
                stream.filter(Files::isDirectory).forEach(paths::add);
            } catch (IOException e) {
                logger.error("扫描用户插件目录失败", e);
            }
        }

        // 项目插件目录
        if (cwd != null) {
            Path projectPluginsDir = PluginPaths.getProjectPluginsDir(cwd);
            if (Files.exists(projectPluginsDir) && Files.isDirectory(projectPluginsDir)) {
                try (Stream<Path> stream = Files.list(projectPluginsDir)) {
                    stream.filter(Files::isDirectory).forEach(paths::add);
                } catch (IOException e) {
                    logger.error("扫描项目插件目录失败", e);
                }
            }
        }

        return paths;
    }

    /**
     * 加载所有插件
     */
    public static List<LoadedPlugin> loadPlugins(Settings settings, Path cwd) {
        List<LoadedPlugin> plugins = new ArrayList<>();
        List<Path> pluginPaths = discoverPluginPaths(cwd);

        Map<String, Boolean> enabledPlugins = settings.getEnabledPlugins();

        for (Path pluginPath : pluginPaths) {
            LoadedPlugin plugin = loadPlugin(pluginPath, enabledPlugins);
            if (plugin != null) {
                plugins.add(plugin);
            }
        }

        logger.info("已加载 {} 个插件", plugins.size());
        return plugins;
    }

    /**
     * 加载单个插件
     */
    public static LoadedPlugin loadPlugin(Path pluginPath, Map<String, Boolean> enabledPlugins) {
        PluginManifest manifest = findManifest(pluginPath);
        if (manifest == null) {
            return null;
        }

        boolean enabled = enabledPlugins.getOrDefault(manifest.getName(), manifest.isEnabledByDefault());
        LoadedPlugin plugin = new LoadedPlugin(manifest, pluginPath, enabled);

        if (!enabled) {
            logger.debug("插件已禁用: {}", manifest.getName());
            return plugin;
        }

        // 加载技能
        plugin.setSkills(loadPluginSkills(pluginPath));

        // 加载 Hook
        plugin.setHooks(loadPluginHooks(pluginPath));

        // 加载 MCP 配置
        plugin.setMcpServers(loadPluginMcp(pluginPath));

        // 【P0-1】加载 plugin 提供的 slash commands（commands/*.md 或 manifest.commandsDir）
        plugin.setCommandPrompts(loadPluginCommands(pluginPath, manifest));

        // 【P0-2】加载 plugin 提供的 subagents（agents/*.md 或 manifest.agentsDir）
        plugin.setAgentDefs(loadPluginAgents(pluginPath, manifest));

        logger.info("已加载插件: {} v{} (skills={}, hooks={}, commands={}, agents={}, mcp={})",
                manifest.getName(), manifest.getVersion(),
                plugin.getSkills().size(),
                plugin.getHooks().size(),
                plugin.getCommandPrompts().size(),
                plugin.getAgentDefs().size(),
                plugin.getMcpServers().size());
        return plugin;
    }

    /**
     * 查找插件清单
     */
    public static PluginManifest findManifest(Path pluginPath) {
        // 尝试 plugin.json
        Path manifestFile = pluginPath.resolve("plugin.json");
        if (Files.exists(manifestFile)) {
            try {
                PluginManifest manifest = MAPPER.readValue(manifestFile.toFile(), PluginManifest.class);
                return manifest;
            } catch (IOException e) {
                logger.error("加载插件清单失败: {}", manifestFile, e);
            }
        }

        // 尝试 .claude-plugin/plugin.json
        Path altManifest = pluginPath.resolve(".claude-plugin").resolve("plugin.json");
        if (Files.exists(altManifest)) {
            try {
                PluginManifest manifest = MAPPER.readValue(altManifest.toFile(), PluginManifest.class);
                return manifest;
            } catch (IOException e) {
                logger.error("加载插件清单失败: {}", altManifest, e);
            }
        }

        return null;
    }

    /**
     * 加载插件技能
     */
    private static List<SkillDefinition> loadPluginSkills(Path pluginPath) {
        List<SkillDefinition> skills = new ArrayList<>();

        // 检查 skills/ 目录
        Path skillsDir = pluginPath.resolve("skills");
        if (Files.exists(skillsDir) && Files.isDirectory(skillsDir)) {
            skills.addAll(SkillLoader.loadSkillsFromDirectory(skillsDir, "plugin"));
        }

        // 检查清单中指定的技能目录
        PluginManifest manifest = findManifest(pluginPath);
        if (manifest != null && manifest.getSkillsDir() != null) {
            Path customSkillsDir = pluginPath.resolve(manifest.getSkillsDir());
            if (Files.exists(customSkillsDir) && Files.isDirectory(customSkillsDir)) {
                skills.addAll(SkillLoader.loadSkillsFromDirectory(customSkillsDir, "plugin"));
            }
        }

        return skills;
    }

    /**
     * 加载插件 Hook
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<Object>> loadPluginHooks(Path pluginPath) {
        Map<String, List<Object>> hooks = new java.util.HashMap<>();

        PluginManifest manifest = findManifest(pluginPath);
        if (manifest != null && manifest.getHooksFile() != null) {
            Path hooksFile = pluginPath.resolve(manifest.getHooksFile());
            if (Files.exists(hooksFile)) {
                try {
                    Map<String, Object> hooksData = MAPPER.readValue(hooksFile.toFile(), Map.class);
                    for (Map.Entry<String, Object> entry : hooksData.entrySet()) {
                        if (entry.getValue() instanceof List) {
                            hooks.put(entry.getKey(), new ArrayList<>((List<?>) entry.getValue()));
                        }
                    }
                } catch (IOException e) {
                    logger.error("加载插件 Hook 失败: {}", hooksFile, e);
                }
            }
        }

        return hooks;
    }

    /**
     * 加载插件 MCP 配置
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadPluginMcp(Path pluginPath) {
        PluginManifest manifest = findManifest(pluginPath);
        if (manifest != null && manifest.getMcpFile() != null) {
            Path mcpFile = pluginPath.resolve(manifest.getMcpFile());
            if (Files.exists(mcpFile)) {
                try {
                    return MAPPER.readValue(mcpFile.toFile(), Map.class);
                } catch (IOException e) {
                    logger.error("加载插件 MCP 配置失败: {}", mcpFile, e);
                }
            }
        }
        return new java.util.HashMap<>();
    }

    /**
     * 【P0-1】加载 plugin 提供的 slash commands。
     *
     * 扫描 <pluginDir>/commands/ 与 <pluginDir>/<manifest.commandsDir>/ 下所有 *.md 文件，
     * 复用 SkillLoader 的 Markdown 解析（YAML front-matter 兼容），source 固定为 "plugin"。
     */
    private static List<SkillDefinition> loadPluginCommands(Path pluginPath, PluginManifest manifest) {
        List<SkillDefinition> commands = new ArrayList<>();

        Path defaultDir = pluginPath.resolve("commands");
        if (Files.exists(defaultDir) && Files.isDirectory(defaultDir)) {
            commands.addAll(SkillLoader.loadSkillsFromDirectory(defaultDir, "plugin"));
        }

        if (manifest != null && manifest.getCommandsDir() != null && !manifest.getCommandsDir().isBlank()) {
            Path customDir = pluginPath.resolve(manifest.getCommandsDir());
            // 与默认目录不同时才扫，避免重复
            if (Files.exists(customDir) && Files.isDirectory(customDir)
                    && !customDir.normalize().equals(defaultDir.normalize())) {
                commands.addAll(SkillLoader.loadSkillsFromDirectory(customDir, "plugin"));
            }
        }

        return commands;
    }

    /**
     * 【P0-2】加载 plugin 提供的 subagents。
     *
     * 扫描 <pluginDir>/agents/ 与 <pluginDir>/<manifest.agentsDir>/ 下所有 *.md 文件，
     * Markdown 正文会被用作子代理的 system prompt（或任务模板）。
     */
    private static List<SkillDefinition> loadPluginAgents(Path pluginPath, PluginManifest manifest) {
        List<SkillDefinition> agents = new ArrayList<>();

        Path defaultDir = pluginPath.resolve("agents");
        if (Files.exists(defaultDir) && Files.isDirectory(defaultDir)) {
            agents.addAll(SkillLoader.loadSkillsFromDirectory(defaultDir, "plugin"));
        }

        if (manifest != null && manifest.getAgentsDir() != null && !manifest.getAgentsDir().isBlank()) {
            Path customDir = pluginPath.resolve(manifest.getAgentsDir());
            if (Files.exists(customDir) && Files.isDirectory(customDir)
                    && !customDir.normalize().equals(defaultDir.normalize())) {
                agents.addAll(SkillLoader.loadSkillsFromDirectory(customDir, "plugin"));
            }
        }

        return agents;
    }
}
