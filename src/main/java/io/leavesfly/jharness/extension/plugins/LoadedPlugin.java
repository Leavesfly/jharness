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
    /**
     * 插件提供的 slash commands（来自 plugin/commands/*.md 或 manifest.commandsDir）。
     *
     * 复用 {@link SkillDefinition} 作为 Markdown + metadata 的简单容器：
     * - name:        命令名（不带 `/`），用于 `/xxx` 调用
     * - description: 命令描述
     * - content:     Markdown 正文，作为 prompt 模板注入
     * - source:      固定 "plugin"
     */
    private List<SkillDefinition> commandPrompts = new ArrayList<>();
    /**
     * 插件提供的子代理定义（来自 plugin/agents/*.md 或 manifest.agentsDir）。
     * 复用 SkillDefinition 承载（content 作为子代理的 system prompt）。
     */
    private List<SkillDefinition> agentDefs = new ArrayList<>();
    /** @deprecated 历史兼容字段，现已被 {@link #commandPrompts} 替代 */
    @Deprecated
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

    /**
     * 【P0-1】插件提供的 slash command 模板（Markdown 形式的 prompt 模板）。
     */
    public List<SkillDefinition> getCommandPrompts() {
        return commandPrompts;
    }

    public void setCommandPrompts(List<SkillDefinition> commandPrompts) {
        this.commandPrompts = commandPrompts == null ? new ArrayList<>() : commandPrompts;
    }

    /**
     * 【P0-2】插件提供的子代理定义（Markdown 形式的 agent 说明）。
     */
    public List<SkillDefinition> getAgentDefs() {
        return agentDefs;
    }

    public void setAgentDefs(List<SkillDefinition> agentDefs) {
        this.agentDefs = agentDefs == null ? new ArrayList<>() : agentDefs;
    }

    public String getName() {
        return manifest != null ? manifest.getName() : "unknown";
    }

    public String getVersion() {
        return manifest != null ? manifest.getVersion() : "0.0.0";
    }
}
