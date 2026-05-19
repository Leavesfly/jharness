package io.leavesfly.jharness.capability.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径规范化与 realPath 解析的纯工具类。
 *
 * 失败一律返回兜底值（原字符串 / null），不抛出，确保规则链不会因为非法输入而被直接放行。
 */
public final class PathNormalizer {

    private static final Logger logger = LoggerFactory.getLogger(PathNormalizer.class);

    private PathNormalizer() {}

    /** 用于规则匹配的归一化字符串（处理 .. / .）。失败回退原串。 */
    public static String normalize(String filePath) {
        try {
            return Paths.get(filePath).normalize().toString();
        } catch (Exception e) {
            logger.debug("路径规范化失败，使用原始字符串匹配: {}", filePath);
            return filePath;
        }
    }

    /** 解析真实物理路径（跟随符号链接），失败返回 null。 */
    public static String resolveRealPath(String filePath) {
        try {
            Path p = Paths.get(filePath);
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                return p.toRealPath().toString();
            }
            return p.toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            logger.debug("realPath 解析失败: {}", filePath);
            return null;
        }
    }
}
