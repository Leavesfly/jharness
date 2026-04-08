package io.leavesfly.jharness.extension.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能注册表
 *
 * 管理所有已注册的技能，支持按名称查询。
 */
public class SkillRegistry {
    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    /**
     * 注册技能
     */
    public void register(SkillDefinition skill) {
        skills.put(skill.getName(), skill);
        logger.debug("已注册技能: {}", skill.getName());
    }

    /**
     * 获取技能
     */
    public SkillDefinition get(String name) {
        return skills.get(name);
    }

    /**
     * 获取所有技能名称
     */
    public Set<String> getSkillNames() {
        return Collections.unmodifiableSet(skills.keySet());
    }

    /**
     * 获取所有技能
     */
    public Collection<SkillDefinition> getAllSkills() {
        return Collections.unmodifiableCollection(skills.values());
    }

    /**
     * 加载内置和用户技能
     */
    public static SkillRegistry withBundled() {
        return SkillLoader.loadSkillRegistry(null);
    }

    /**
     * 加载包含项目技能的注册表
     */
    public static SkillRegistry withProject(Path cwd) {
        return SkillLoader.loadSkillRegistry(cwd);
    }

    public int size() {
        return skills.size();
    }
}
