package io.leavesfly.jharness.extension.skills;

import java.nio.file.Path;

/**
 * 技能目录路径工具类
 */
public class SkillPaths {
    
    /**
     * 获取用户技能目录 (~/.jharness/skills/)
     */
    public static Path getUserSkillsDir() {
        return Path.of(System.getProperty("user.home"), ".jharness", "skills");
    }

    /**
     * 获取内置技能目录 (classpath)
     */
    public static Path getBundledSkillsDir() {
        // 内置技能从 classpath 加载，无物理目录
        return null;
    }

    /**
     * 获取项目技能目录 (<cwd>/.jharness/skills/)
     */
    public static Path getProjectSkillsDir(Path cwd) {
        if (cwd == null) {
            return null;
        }
        return cwd.resolve(".jharness").resolve("skills");
    }
}
