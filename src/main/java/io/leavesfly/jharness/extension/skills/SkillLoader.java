package io.leavesfly.jharness.extension.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 技能加载器
 * <p>
 * 从以下位置加载技能：
 * 1. 内置技能 (classpath resources)
 * 2. 用户技能 (~/.jharness/skills/*.md)
 * 3. 项目技能 (<cwd>/.jharness/skills/*.md)
 */
public class SkillLoader {
    private static final Logger logger = LoggerFactory.getLogger(SkillLoader.class);

    // 缓存字段
    private static volatile SkillRegistry cachedRegistry;
    private static volatile Path cachedProjectPath;

    /**
     * 加载完整的技能注册表
     */
    public static SkillRegistry loadSkillRegistry(Path cwd) {
        // 缓存检查：如果项目路径相同，直接返回缓存
        if (cachedRegistry != null && java.util.Objects.equals(cachedProjectPath, cwd)) {
            return cachedRegistry;
        }

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

        // 更新缓存
        cachedRegistry = registry;
        cachedProjectPath = cwd;

        return registry;
    }

    private static final String BUNDLED_SKILLS_DIR = "skills";
    private static final String SKILL_FILE_NAME = "SKILL.md";

    /**
     * 获取内置技能列表
     * <p>
     * 从 classpath 的 resources/skills/ 目录加载预置技能文件。
     * 支持两种目录结构：
     * 1. skills/{name}/SKILL.md（子目录形式）
     * 2. skills/{name}.md（扁平文件形式）
     */
    public static List<SkillDefinition> getBundledSkills() {
        List<SkillDefinition> skills = new ArrayList<>();
        ClassLoader classLoader = SkillLoader.class.getClassLoader();

        // 扫描 classpath 中 skills/ 目录下的子目录和文件
        try {
            URL skillsDirUrl = classLoader.getResource(BUNDLED_SKILLS_DIR);
            if (skillsDirUrl == null) {
                logger.warn("未找到内置技能目录: {}", BUNDLED_SKILLS_DIR);
                return skills;
            }

            java.net.URI skillsDirUri = skillsDirUrl.toURI();

            // 处理 JAR 包内和文件系统两种情况
            if ("jar".equals(skillsDirUri.getScheme())) {
                skills.addAll(loadBundledSkillsFromJar(skillsDirUri, classLoader));
            } else {
                skills.addAll(loadBundledSkillsFromFileSystem(Path.of(skillsDirUri), classLoader));
            }
        } catch (Exception e) {
            logger.error("扫描内置技能目录失败", e);
        }

        return skills;
    }

    /**
     * 从文件系统加载内置技能（开发环境）
     */
    private static List<SkillDefinition> loadBundledSkillsFromFileSystem(Path skillsDir, ClassLoader classLoader) {
        List<SkillDefinition> skills = new ArrayList<>();
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            return skills;
        }

        try (Stream<Path> entries = Files.list(skillsDir)) {
            List<Path> sortedEntries = entries.sorted().toList();
            for (Path entry : sortedEntries) {
                if (Files.isDirectory(entry)) {
                    // 子目录形式：skills/{name}/SKILL.md
                    Path skillFile = entry.resolve(SKILL_FILE_NAME);
                    if (Files.exists(skillFile)) {
                        loadBundledSkillFile(skillFile, entry.getFileName().toString(), skills);
                    }
                } else if (entry.toString().endsWith(".md")) {
                    // 扁平文件形式：skills/{name}.md
                    loadBundledSkillFile(entry, entry.getFileName().toString().replace(".md", ""), skills);
                }
            }
        } catch (IOException e) {
            logger.error("扫描内置技能文件系统目录失败: {}", skillsDir, e);
        }

        return skills;
    }

    /**
     * 从 JAR 包加载内置技能
     */
    private static List<SkillDefinition> loadBundledSkillsFromJar(java.net.URI jarUri, ClassLoader classLoader) {
        List<SkillDefinition> skills = new ArrayList<>();

        try (FileSystem jarFs = FileSystems.newFileSystem(jarUri, java.util.Collections.emptyMap())) {
            Path skillsDir = jarFs.getPath(BUNDLED_SKILLS_DIR);
            if (!Files.exists(skillsDir)) {
                return skills;
            }

            try (Stream<Path> entries = Files.list(skillsDir)) {
                List<Path> sortedEntries = entries.sorted().toList();
                for (Path entry : sortedEntries) {
                    if (Files.isDirectory(entry)) {
                        Path skillFile = entry.resolve(SKILL_FILE_NAME);
                        if (Files.exists(skillFile)) {
                            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
                            String dirName = entry.getFileName().toString();
                            String[] nameDesc = parseSkillMarkdown(dirName, content);
                            skills.add(new SkillDefinition(nameDesc[0], nameDesc[1], content, "bundled"));
                            logger.debug("已加载内置技能(jar): {}", nameDesc[0]);
                        }
                    } else if (entry.toString().endsWith(".md")) {
                        String content = Files.readString(entry, StandardCharsets.UTF_8);
                        String defaultName = entry.getFileName().toString().replace(".md", "");
                        String[] nameDesc = parseSkillMarkdown(defaultName, content);
                        skills.add(new SkillDefinition(nameDesc[0], nameDesc[1], content, "bundled"));
                        logger.debug("已加载内置技能(jar): {}", nameDesc[0]);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("从 JAR 加载内置技能失败", e);
        }

        return skills;
    }

    /**
     * 加载单个内置技能文件
     */
    private static void loadBundledSkillFile(Path file, String defaultName, List<SkillDefinition> skills) {
        try {
            String content = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            String[] nameDesc = parseSkillMarkdown(defaultName, content);
            skills.add(new SkillDefinition(nameDesc[0], nameDesc[1], content, "bundled"));
            logger.debug("已加载内置技能: {}", nameDesc[0]);
        } catch (IOException e) {
            logger.error("加载内置技能文件失败: {}", file, e);
        }
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
     * <p>
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