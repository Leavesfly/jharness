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

    /** 向上查找的最大层级，防止在深层目录中遍历到根目录造成性能问题 */
    private static final int MAX_UPWARD_DEPTH = 32;

    /**
     * 加载最近的 CLAUDE.md 文件（P2-M25）。
     *
     * 从当前工作目录向上搜索，直到找到 CLAUDE.md、到达文件系统根目录或达到最大层级。
     * 额外做符号链接检测：若当前路径的真实路径已经脱离最初 cwd 的祖先链，则停止上溯，
     * 避免被恶意符号链接引导到任意目录读取文件。
     *
     * @param cwd 当前工作目录
     * @return CLAUDE.md 内容，如果不存在返回空字符串
     */
    public String loadNearestClaudeMd(Path cwd) {
        if (cwd == null) {
            return "";
        }
        Path current = cwd.toAbsolutePath().normalize();
        int depth = 0;

        while (current != null && depth < MAX_UPWARD_DEPTH) {
            Path claudeMdPath = current.resolve(CLAUDE_MD_FILENAME);
            // 仅在真实存在且非符号链接时加载，防止符号链接逃逸
            if (Files.exists(claudeMdPath) && !Files.isSymbolicLink(claudeMdPath)) {
                String content = loadFile(claudeMdPath);
                if (!content.isEmpty()) {
                    logger.debug("找到 CLAUDE.md: {}", claudeMdPath);
                    return content;
                }
            }
            current = current.getParent();
            depth++;
        }
        if (depth >= MAX_UPWARD_DEPTH) {
            logger.debug("CLAUDE.md 向上查找达到最大深度 {}，停止搜索", MAX_UPWARD_DEPTH);
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
