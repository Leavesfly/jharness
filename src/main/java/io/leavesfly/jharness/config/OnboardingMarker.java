package io.leavesfly.jharness.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Onboarding 标记文件管理工具。
 *
 * <p>职责：维护 {@code ~/.jharness/.onboarded} 标记文件的生命周期，
 * 用于判断用户是否已完成首次启动的 onboarding 向导。
 *
 * <p>下沉到 {@code config} 包的原因：
 * 该标记本质上是配置目录内的一个文件，{@code command} / {@code app} 两层都需要操作它
 * （{@code app.onboarding} 在向导完成时写入；{@code command} 的 {@code /onboarding}
 * 命令在用户请求重置时删除）。把文件 IO 放在 {@code config} 包内，避免
 * {@code command -> app} 的反向依赖（违反 ArchUnit 守卫），同时让标记
 * 文件名常量保持单一来源。
 */
public final class OnboardingMarker {

    private static final Logger logger = LoggerFactory.getLogger(OnboardingMarker.class);

    /** 标记文件名（位于配置目录根下的隐藏文件）。 */
    public static final String FILE_NAME = ".onboarded";

    private OnboardingMarker() {}

    /** 标记文件的绝对路径，基于默认配置目录 {@code ~/.jharness/}。 */
    public static Path defaultMarkerPath() {
        return Settings.getDefaultConfigDir().resolve(FILE_NAME);
    }

    /** 在指定配置目录下解析标记文件路径（测试友好）。 */
    public static Path markerPath(Path configDir) {
        if (configDir == null) {
            throw new IllegalArgumentException("configDir 不能为空");
        }
        return configDir.resolve(FILE_NAME);
    }

    /** 默认配置目录下，onboarding 是否已完成。 */
    public static boolean isOnboarded() {
        return Files.exists(defaultMarkerPath());
    }

    /** 指定配置目录下，onboarding 是否已完成。 */
    public static boolean isOnboarded(Path configDir) {
        return Files.exists(markerPath(configDir));
    }

    /**
     * 在默认配置目录下写入 onboarded 标记。
     *
     * @return 成功返回 true；失败仅记录日志并返回 false（不抛出异常，避免阻断主流程）
     */
    public static boolean markOnboarded() {
        return markOnboarded(Settings.getDefaultConfigDir());
    }

    /**
     * 在指定配置目录下写入 onboarded 标记。文件内容为 onboard 时间戳，便于排查。
     */
    public static boolean markOnboarded(Path configDir) {
        try {
            Files.createDirectories(configDir);
            Path marker = markerPath(configDir);
            String content = "onboarded_at=" + Instant.now() + System.lineSeparator();
            Files.writeString(marker, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException e) {
            logger.warn("写入 onboarded 标记失败: dir={} ({})", configDir, e.getMessage());
            return false;
        }
    }

    /** 清除默认配置目录下的 onboarded 标记，使下次启动重新触发向导。 */
    public static boolean clearMarker() {
        return clearMarker(Settings.getDefaultConfigDir());
    }

    /** 清除指定配置目录下的 onboarded 标记。 */
    public static boolean clearMarker(Path configDir) {
        try {
            return Files.deleteIfExists(markerPath(configDir));
        } catch (IOException e) {
            logger.warn("删除 onboarded 标记失败: dir={} ({})", configDir, e.getMessage());
            return false;
        }
    }
}
