package io.leavesfly.jharness.app.bootstrap;

import io.leavesfly.jharness.capability.coordination.TeamRecord;
import io.leavesfly.jharness.capability.coordination.TeamRegistry;
import io.leavesfly.jharness.capability.hook.HookEvent;
import io.leavesfly.jharness.capability.hook.HookRegistry;
import io.leavesfly.jharness.command.CommandRegistry;
import io.leavesfly.jharness.command.PluginSlashCommand;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.extension.plugins.LoadedPlugin;
import io.leavesfly.jharness.extension.plugins.PluginLoader;
import io.leavesfly.jharness.extension.skills.SkillDefinition;
import io.leavesfly.jharness.extension.skills.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * 插件加载与注入工具集（4.8 拆分自 JHarnessApplication）。
 *
 * 负责：
 * <ul>
 *   <li>{@link #loadPluginsQuietly}：扫描用户/项目级插件，合并 skills 与 hooks；</li>
 *   <li>{@link #registerPluginCommands}：把插件 commands/*.md 注册为 slash command；</li>
 *   <li>{@link #registerPluginAgents}：把插件 agents 注册到 TeamRegistry；</li>
 *   <li>{@link #mapHookEvent}：字符串 → HookEvent 枚举映射。</li>
 * </ul>
 */
public final class PluginBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(PluginBootstrap.class);

    private PluginBootstrap() {}

    public static List<LoadedPlugin> loadPluginsQuietly(Settings settings, Path cwd,
                                                       SkillRegistry skillRegistry,
                                                       HookRegistry hookRegistry) {
        try {
            List<LoadedPlugin> plugins = PluginLoader.loadPlugins(settings, cwd);
            int totalCommands = 0;
            int totalAgents = 0;
            for (LoadedPlugin plugin : plugins) {
                if (!plugin.isEnabled()) {
                    continue;
                }
                plugin.getSkills().forEach(skillRegistry::register);
                plugin.getHooks().forEach((eventName, defs) -> {
                    HookEvent ev = mapHookEvent(eventName);
                    if (ev != null && defs != null) {
                        defs.forEach(def -> hookRegistry.register(ev, def));
                    } else {
                        logger.warn("插件 {} 声明了未知 Hook 事件: {}", plugin.getName(), eventName);
                    }
                });
                totalCommands += plugin.getCommandPrompts().size();
                totalAgents += plugin.getAgentDefs().size();
            }
            logger.info(
                    "插件系统: 加载 {} 个插件, 合并后技能 {} 个, Hook {} 个, 插件 slash commands {} 个, 插件 agents {} 个",
                    plugins.size(), skillRegistry.getAllSkills().size(), hookRegistry.size(),
                    totalCommands, totalAgents);
            return plugins;
        } catch (Exception e) {
            logger.warn("加载插件失败（忽略并继续）", e);
            return List.of();
        }
    }

    public static int registerPluginCommands(CommandRegistry registry, List<LoadedPlugin> plugins) {
        if (registry == null || plugins == null || plugins.isEmpty()) return 0;
        int added = 0;
        for (LoadedPlugin plugin : plugins) {
            if (!plugin.isEnabled()) continue;
            for (SkillDefinition def : plugin.getCommandPrompts()) {
                if (def == null || def.getName() == null || def.getName().isBlank()) continue;
                if (registry.hasCommand(def.getName())) {
                    logger.warn("插件 {} 的命令 /{} 与已注册命令冲突，跳过", plugin.getName(), def.getName());
                    continue;
                }
                registry.register(PluginSlashCommand.fromSkill(def, plugin.getName()));
                added++;
            }
        }
        if (added > 0) {
            logger.info("已注册 {} 个插件 slash command", added);
        }
        return added;
    }

    public static int registerPluginAgents(TeamRegistry teamRegistry, List<LoadedPlugin> plugins) {
        if (teamRegistry == null || plugins == null || plugins.isEmpty()) return 0;
        int added = 0;
        for (LoadedPlugin plugin : plugins) {
            if (!plugin.isEnabled()) continue;
            for (SkillDefinition def : plugin.getAgentDefs()) {
                if (def == null || def.getName() == null || def.getName().isBlank()) continue;
                if (teamRegistry.getTeam(def.getName()) != null) {
                    logger.warn("插件 {} 的 agent {} 与已存在团队重名，跳过", plugin.getName(), def.getName());
                    continue;
                }
                TeamRecord team = teamRegistry.createTeam(def.getName(), def.getDescription());
                team.setMetadata("source", "plugin");
                team.setMetadata("plugin", plugin.getName());
                team.setMetadata("systemPrompt", def.getContent());
                added++;
            }
        }
        if (added > 0) {
            logger.info("已注册 {} 个插件 subagent 到 TeamRegistry", added);
        }
        return added;
    }

    public static HookEvent mapHookEvent(String name) {
        if (name == null) return null;
        String normalized = name.trim().toLowerCase().replace('-', '_');
        for (HookEvent ev : HookEvent.values()) {
            if (ev.name().toLowerCase().equals(normalized)
                    || ev.getValue().equalsIgnoreCase(name.trim())) {
                return ev;
            }
        }
        return null;
    }
}
