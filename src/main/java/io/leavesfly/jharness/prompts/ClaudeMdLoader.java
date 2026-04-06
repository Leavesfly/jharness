package io.leavesfly.jharness.prompts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLAUDE.md 加载器
 *
 * 自动加载项目根目录的 CLAUDE.md 文件，作为项目上下文注入到系统提示词中。
 * 支持多层级 CLAUDE.md 文件（项目根目录、子目录）。
 */
public class ClaudeMdLoader {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeMdLoader.class);
    private static final String CLAUDE_MD_FILENAME = "CLAUDE.md";

    /**
     * 加载 CLAUDE.md 文件
     *
     * @param projectDir 项目目录
     * @return CLAUDE.md 内容，如果不存在返回空字符串
     */
    public String loadClaudeMd(Path projectDir) {
        Path claudeMdPath = projectDir.resolve(CLAUDE_MD_FILENAME);
        return loadFile(claudeMdPath);
    }

    /**
     * 加载最近的 CLAUDE.md 文件
     *
     * 从当前工作目录向上搜索，直到找到 CLAUDE.md 或到达文件系统根目录。
     *
     * @param cwd 当前工作目录
     * @return CLAUDE.md 内容，如果不存在返回空字符串
     */
    public String loadNearestClaudeMd(Path cwd) {
        Path current = cwd.toAbsolutePath();

        while (current != null) {
            Path claudeMdPath = current.resolve(CLAUDE_MD_FILENAME);
            String content = loadFile(claudeMdPath);
            if (!content.isEmpty()) {
                logger.debug("找到 CLAUDE.md: {}", claudeMdPath);
                return content;
            }
            current = current.getParent();
        }

        return "";
    }

    /**
     * 加载指定路径的 CLAUDE.md 文件
     */
    private String loadFile(Path path) {
        if (Files.exists(path) && Files.isRegularFile(path)) {
            try {
                String content = Files.readString(path);
                logger.info("已加载 CLAUDE.md: {} ({} 字符)", path, content.length());
                return content;
            } catch (IOException e) {
                logger.warn("加载 CLAUDE.md 失败: {}", e.getMessage());
                return "";
            }
        }
        return "";
    }

    /**
     * 检查是否存在 CLAUDE.md 文件
     */
    public boolean hasClaudeMd(Path projectDir) {
        return Files.exists(projectDir.resolve(CLAUDE_MD_FILENAME));
    }
}
