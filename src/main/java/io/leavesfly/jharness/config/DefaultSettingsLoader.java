package io.leavesfly.jharness.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.util.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 默认配置加载器。
 *
 * <p>职责：从 classpath 路径 {@code defaults/settings.json} 加载内置默认配置，
 * 作为 {@link Settings} 的"应用级默认值"打底（位于硬编码兜底之上、用户配置文件之下）。
 *
 * <p>加载优先级（从低到高，后者覆盖前者）：
 * <ol>
 *   <li>Settings 字段硬编码默认值（new Settings() 构造器初始值）</li>
 *   <li>classpath {@code defaults/settings.json}（本类负责）</li>
 *   <li>用户配置文件 {@code ~/.jharness/settings.json}</li>
 *   <li>环境变量（OPENAI_API_KEY / JHARNESS_MODEL 等）</li>
 *   <li>CLI 选项（{@code --model} 等）</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>失败不阻断</b>：classpath 资源缺失或解析失败时返回 {@link Optional#empty()}，由调用方降级；</li>
 *   <li><b>缓存</b>：classpath 资源在进程生命周期内不会变化，首次加载后缓存 JsonNode；</li>
 *   <li><b>可测试</b>：提供 {@link #loadFromPath(Path)} 用于测试场景注入自定义路径。</li>
 * </ul>
 */
public final class DefaultSettingsLoader {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSettingsLoader.class);
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;

    /** classpath 资源路径，与 {@link SettingsBootstrap#DEFAULT_SETTINGS_RESOURCE} 保持一致。 */
    public static final String CLASSPATH_RESOURCE = "defaults/settings.json";

    /** 缓存的 classpath 默认配置 JsonNode（懒加载，首次读取后复用）。 */
    private static volatile JsonNode cachedDefaults;
    /** 标记 classpath 资源是否已尝试加载过，避免反复打印同样的警告日志。 */
    private static volatile boolean classpathProbed;

    private DefaultSettingsLoader() {}

    /**
     * 加载 classpath 下的默认配置 JSON 节点。
     *
     * @return 解析后的 {@link JsonNode}，若资源不存在或解析失败则返回 {@link Optional#empty()}
     */
    public static Optional<JsonNode> loadClasspathDefaults() {
        JsonNode cached = cachedDefaults;
        if (cached != null) {
            return Optional.of(cached);
        }
        if (classpathProbed) {
            // 已探测过且为空，直接返回，避免重复 IO
            return Optional.empty();
        }
        synchronized (DefaultSettingsLoader.class) {
            if (cachedDefaults != null) {
                return Optional.of(cachedDefaults);
            }
            if (classpathProbed) {
                return Optional.empty();
            }
            classpathProbed = true;

            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = DefaultSettingsLoader.class.getClassLoader();
            }
            try (InputStream in = cl.getResourceAsStream(CLASSPATH_RESOURCE)) {
                if (in == null) {
                    logger.warn("未找到默认配置 classpath 资源: {}", CLASSPATH_RESOURCE);
                    return Optional.empty();
                }
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                JsonNode root = MAPPER.readTree(json);
                if (!root.isObject()) {
                    logger.warn("默认配置根节点不是 JSON 对象: {}", CLASSPATH_RESOURCE);
                    return Optional.empty();
                }
                cachedDefaults = root;
                logger.debug("已加载 classpath 默认配置: {}", CLASSPATH_RESOURCE);
                return Optional.of(root);
            } catch (Exception e) {
                logger.warn("加载 classpath 默认配置失败: {} ({})", CLASSPATH_RESOURCE, e.getMessage());
                return Optional.empty();
            }
        }
    }

    /**
     * 从指定路径加载 JSON（测试场景使用）。
     *
     * @param path 配置文件路径
     * @return 解析后的 {@link JsonNode}，文件不存在或解析失败时返回 {@link Optional#empty()}
     */
    public static Optional<JsonNode> loadFromPath(Path path) {
        if (path == null || !Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(path);
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) {
                logger.warn("配置文件根节点不是 JSON 对象: {}", path);
                return Optional.empty();
            }
            return Optional.of(root);
        } catch (Exception e) {
            logger.warn("加载配置文件失败: {} ({})", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 仅用于测试：重置缓存，使下次 {@link #loadClasspathDefaults()} 重新探测 classpath。
     */
    static void resetCacheForTesting() {
        synchronized (DefaultSettingsLoader.class) {
            cachedDefaults = null;
            classpathProbed = false;
        }
    }
}
