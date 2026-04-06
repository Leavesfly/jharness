package io.leavesfly.jharness.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 技能加载器
 * 
 * 从以下位置加载技能：
 * 1. 内置技能 (classpath resources)
 * 2. 用户技能 (~/.jharness/skills/*.md)
 * 3. 项目技能 (<cwd>/.jharness/skills/*.md)
 */
public class SkillLoader {
    private static final Logger logger = LoggerFactory.getLogger(SkillLoader.class);

    /**
     * 加载完整的技能注册表
     */
    public static SkillRegistry loadSkillRegistry(Path cwd) {
        SkillRegistry registry = new SkillRegistry();
        
        // 加载内置技能
        List<SkillDefinition> bundled = getBundledSkills();
        for (SkillDefinition skill : bundled) {
            registry.register(skill);
        }
        logger.debug("已加载 {} 个内置技能", bundled.size());

        // 加载用户技能
        Path userSkillsDir = getUserSkillsDir();
        List<SkillDefinition> userSkills = loadSkillsFromDirectory(userSkillsDir, "user");
        for (SkillDefinition skill : userSkills) {
            registry.register(skill);
        }
        logger.debug("已加载 {} 个用户技能", userSkills.size());

        // 加载项目技能
        if (cwd != null) {
            Path projectSkillsDir = cwd.resolve(".jharness").resolve("skills");
            List<SkillDefinition> projectSkills = loadSkillsFromDirectory(projectSkillsDir, "project");
            for (SkillDefinition skill : projectSkills) {
                registry.register(skill);
            }
            logger.debug("已加载 {} 个项目技能", projectSkills.size());
        }

        return registry;
    }

    /**
     * 内置技能文件名列表（classpath resources/skills/ 下的 .md 文件）
     */
    private static final String[] BUNDLED_SKILL_FILES = {
            "code-review.md",
            "explain-code.md",
            "refactor.md",
            "write-tests.md",
            "debug.md"
    };

    /**
     * 获取内置技能列表
     *
     * 从 classpath 的 resources/skills/ 目录加载预置技能文件。
     */
    public static List<SkillDefinition> getBundledSkills() {
        List<SkillDefinition> skills = new ArrayList<>();
        ClassLoader classLoader = SkillLoader.class.getClassLoader();

        for (String fileName : BUNDLED_SKILL_FILES) {
            String resourcePath = "skills/" + fileName;
            try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    continue;
                }
                String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                String defaultName = fileName.replace(".md", "");
                String[] nameDesc = parseSkillMarkdown(defaultName, content);
                skills.add(new SkillDefinition(nameDesc[0], nameDesc[1], content, "bundled"));
                logger.debug("已加载内置技能: {}", nameDesc[0]);
            } catch (Exception e) {
                logger.debug("加载内置技能失败: {}", fileName, e);
            }
        }

        return skills;
    }

    /**
     * 获取用户技能目录
     */
    public static Path getUserSkillsDir() {
        Path skillsDir = Path.of(System.getProperty("user.home"), ".jharness", "skills");
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            logger.error("创建用户技能目录失败", e);
        }
        return skillsDir;
    }

    /**
     * 从目录加载所有 .md 技能文件
     */
    public static List<SkillDefinition> loadSkillsFromDirectory(Path dir, String source) {
        List<SkillDefinition> skills = new ArrayList<>();
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return skills;
        }

        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> mdFiles = stream
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .toList();

            for (Path file : mdFiles) {
                try {
                    String content = Files.readString(file);
                    String[] nameDesc = parseSkillMarkdown(file.getFileName().toString().replace(".md", ""), content);
                    skills.add(new SkillDefinition(nameDesc[0], nameDesc[1], content, source));
                } catch (IOException e) {
                    logger.error("加载技能文件失败: {}", file, e);
                }
            }
        } catch (IOException e) {
            logger.error("扫描技能目录失败: {}", dir, e);
        }

        return skills;
    }

    /**
     * 解析技能 Markdown 文件
     * 
     * 支持 YAML frontmatter (--- ... ---) 或回退到标题+段落
     */
    public static String[] parseSkillMarkdown(String defaultName, String content) {
        String name = defaultName;
        String description = "";

        String[] lines = content.split("\n");

        // 尝试 YAML frontmatter
        if (lines.length > 0 && lines[0].trim().equals("---")) {
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].trim().equals("---")) {
                    // 解析 frontmatter 字段
                    for (int j = 1; j < i; j++) {
                        String line = lines[j].trim();
                        if (line.startsWith("name:")) {
                            String val = line.substring(5).trim().replaceAll("^['\"]|['\"]$", "");
                            if (!val.isEmpty()) {
                                name = val;
                            }
                        } else if (line.startsWith("description:")) {
                            String val = line.substring(12).trim().replaceAll("^['\"]|['\"]$", "");
                            if (!val.isEmpty()) {
                                description = val;
                            }
                        }
                    }
                    break;
                }
            }
        }

        // 回退：从标题和第一段提取
        if (description.isEmpty()) {
            for (String line : lines) {
                String stripped = line.trim();
                if (stripped.startsWith("# ")) {
                    if (name.equals(defaultName)) {
                        name = stripped.substring(2).trim();
                    }
                    continue;
                }
                if (!stripped.isEmpty() && !stripped.startsWith("---") && !stripped.startsWith("#")) {
                    description = stripped.length() > 200 ? stripped.substring(0, 200) : stripped;
                    break;
                }
            }
        }

        if (description.isEmpty()) {
            description = "Skill: " + name;
        }

        return new String[]{name, description};
    }
}
