package io.leavesfly.jharness.extension.plugins;

import io.leavesfly.jharness.extension.skills.SkillDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 已加载的插件运行时表示
 */
public class LoadedPlugin {
    private PluginManifest manifest;
    private Path path;
    private boolean enabled;
    private List<SkillDefinition> skills = new ArrayList<>();
    private Map<String, List<Object>> hooks = new HashMap<>();
    private Map<String, Object> mcpServers = new HashMap<>();
    private List<SkillDefinition> commands = new ArrayList<>();

    public LoadedPlugin() {}

    public LoadedPlugin(PluginManifest manifest, Path path, boolean enabled) {
        this.manifest = manifest;
        this.path = path;
        this.enabled = enabled;
    }

    public PluginManifest getManifest() {
        return manifest;
    }

    public void setManifest(PluginManifest manifest) {
        this.manifest = manifest;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<SkillDefinition> getSkills() {
        return skills;
    }

    public void setSkills(List<SkillDefinition> skills) {
        this.skills = skills;
    }

    public Map<String, List<Object>> getHooks() {
        return hooks;
    }

    public void setHooks(Map<String, List<Object>> hooks) {
        this.hooks = hooks;
    }

    public Map<String, Object> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, Object> mcpServers) {
        this.mcpServers = mcpServers;
    }

    public List<SkillDefinition> getCommands() {
        return commands;
    }

    public void setCommands(List<SkillDefinition> commands) {
        this.commands = commands;
    }

    public String getName() {
        return manifest != null ? manifest.getName() : "unknown";
    }

    public String getVersion() {
        return manifest != null ? manifest.getVersion() : "0.0.0";
    }
}
