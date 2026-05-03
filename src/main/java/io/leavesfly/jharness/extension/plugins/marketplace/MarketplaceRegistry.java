package io.leavesfly.jharness.extension.plugins.marketplace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.leavesfly.jharness.extension.plugins.PluginPaths;
import io.leavesfly.jharness.util.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【P2】Marketplace 注册表。
 *
 * <p>负责：
 * <ul>
 *   <li>把用户 add 过的 marketplace 记录持久化到 {@code ~/.jharness/plugins/marketplaces.json}</li>
 *   <li>解析 marketplace 根目录下的 {@code .claude-plugin/marketplace.json} 或 {@code marketplace.json}</li>
 *   <li>列出 marketplace 内的插件引用</li>
 * </ul>
 *
 * <p>为最小实现，当前仅支持 <b>本地目录</b> 形式的 marketplace（{@code source} 为本机绝对或相对路径）。
 * Git 远程克隆留作后续扩展（见 {@link #addFromLocal(String, Path)} 的注释）。
 */
public class MarketplaceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(MarketplaceRegistry.class);
    private static final ObjectMapper MAPPER = JacksonUtils.MAPPER;

    /** 持久化文件名（落在 ~/.jharness/plugins/ 下）。 */
    static final String STATE_FILE_NAME = "marketplaces.json";

    /** name → 本地根目录（Map 以保持插入顺序）。 */
    private final Map<String, Path> marketplaces = new LinkedHashMap<>();

    private final Path stateFile;

    public MarketplaceRegistry() {
        this(PluginPaths.getUserPluginsDir().resolve(STATE_FILE_NAME));
    }

    /** 构造并立即从 state 文件加载现有记录。 */
    public MarketplaceRegistry(Path stateFile) {
        this.stateFile = stateFile;
        load();
    }

    /** 加载持久化状态；格式 {@code {"name": "/abs/path", ...}}。解析失败则回退为空。 */
    @SuppressWarnings("unchecked")
    private void load() {
        if (stateFile == null || !Files.exists(stateFile)) return;
        try {
            Map<String, String> raw = MAPPER.readValue(
                    Files.readAllBytes(stateFile),
                    new TypeReference<Map<String, String>>() {});
            raw.forEach((name, path) -> {
                if (name == null || name.isBlank() || path == null) return;
                marketplaces.put(name, Path.of(path));
            });
        } catch (IOException | RuntimeException e) {
            logger.warn("读取 marketplaces 状态文件失败，忽略: {}", stateFile, e);
        }
    }

    private void persist() {
        if (stateFile == null) return;
        try {
            Files.createDirectories(stateFile.getParent());
            Map<String, String> raw = new LinkedHashMap<>();
            marketplaces.forEach((k, v) -> raw.put(k, v.toAbsolutePath().normalize().toString()));
            Files.writeString(stateFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(raw));
        } catch (IOException e) {
            logger.warn("持久化 marketplaces 状态文件失败: {}", stateFile, e);
        }
    }

    /**
     * 从本地目录添加 marketplace。目录下必须能找到
     * {@code .claude-plugin/marketplace.json} 或 {@code marketplace.json}。
     *
     * @param name 本地命名；null 时会用 marketplace.json 里的 name 或目录名兜底
     * @param dir  marketplace 根目录
     * @return 最终使用的 marketplace 名
     * @throws IllegalArgumentException 清单不存在或清单 name 非法
     */
    public String addFromLocal(String name, Path dir) {
        if (dir == null) {
            throw new IllegalArgumentException("marketplace 目录不能为空");
        }
        Path root = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("marketplace 目录不存在: " + root);
        }
        MarketplaceDefinition def = parseManifest(root);
        if (def == null) {
            throw new IllegalArgumentException(
                    "marketplace 清单不存在：需要 .claude-plugin/marketplace.json 或 marketplace.json");
        }
        String effectiveName = (name == null || name.isBlank())
                ? (def.getName() == null || def.getName().isBlank()
                    ? root.getFileName().toString()
                    : def.getName())
                : name;
        if (effectiveName.contains("/") || effectiveName.contains("\\")
                || effectiveName.contains("..") || effectiveName.isBlank()) {
            throw new IllegalArgumentException("非法的 marketplace 名: " + effectiveName);
        }
        marketplaces.put(effectiveName, root);
        persist();
        logger.info("已添加 marketplace: {} -> {}", effectiveName, root);
        return effectiveName;
    }

    public boolean remove(String name) {
        boolean removed = marketplaces.remove(name) != null;
        if (removed) persist();
        return removed;
    }

    public Map<String, Path> list() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(marketplaces));
    }

    public Path getRoot(String name) {
        return marketplaces.get(name);
    }

    /**
     * 加载指定 marketplace 的清单（实时读取，未做缓存，便于 marketplace 作者热改）。
     */
    public MarketplaceDefinition loadDefinition(String name) {
        Path root = marketplaces.get(name);
        if (root == null) return null;
        return parseManifest(root);
    }

    /** 列出 marketplace 内的插件引用。 */
    public List<MarketplaceDefinition.PluginRef> listPlugins(String name) {
        MarketplaceDefinition def = loadDefinition(name);
        if (def == null || def.getPlugins() == null) return List.of();
        return new ArrayList<>(def.getPlugins());
    }

    /**
     * 根据 marketplace 名 + 插件名解析出该插件源目录（绝对路径）。
     *
     * <p>source 为相对路径时，基准是 marketplace 根目录；绝对路径则直接使用。
     * 不支持 git+ 前缀（会抛 UnsupportedOperationException，留给后续扩展）。
     *
     * <p><b>【安全】</b>解析后的路径必须仍位于 marketplace root 目录内（对相对路径），
     * 防止恶意 marketplace.json 通过 {@code "source": "../../../etc/passwd"} 之类的
     * 路径遍历逃逸到 root 之外。对于声明为绝对路径的 source，我们视为 marketplace
     * 作者的显式意图（通常用于指向 monorepo 中 shared 目录），仅在日志中提示，不做拦截。
     */
    public Path resolvePluginSource(String marketplaceName, String pluginName) {
        Path root = marketplaces.get(marketplaceName);
        if (root == null) {
            throw new IllegalArgumentException("marketplace 未注册: " + marketplaceName);
        }
        MarketplaceDefinition def = parseManifest(root);
        if (def == null) {
            throw new IllegalStateException("marketplace 清单已不可用: " + marketplaceName);
        }
        for (MarketplaceDefinition.PluginRef ref : def.getPlugins()) {
            if (ref == null || !pluginName.equals(ref.getName())) continue;
            String src = ref.getSource();
            if (src == null || src.isBlank()) {
                throw new IllegalStateException("marketplace " + marketplaceName
                        + " 中插件 " + pluginName + " 缺少 source 字段");
            }
            if (src.startsWith("git+") || src.startsWith("http://") || src.startsWith("https://")) {
                throw new UnsupportedOperationException(
                        "当前版本仅支持本地 marketplace 源，git/http 源尚未实现: " + src);
            }
            Path rawPath = Path.of(src);
            boolean wasRelative = !rawPath.isAbsolute();
            Path abs = (wasRelative ? root.resolve(src) : rawPath).toAbsolutePath().normalize();

            // 安全校验：相对路径必须仍落在 marketplace root 内，阻断 ".." 逃逸
            if (wasRelative) {
                Path normalizedRoot = root.toAbsolutePath().normalize();
                if (!abs.startsWith(normalizedRoot)) {
                    throw new SecurityException(
                            "marketplace " + marketplaceName + " 中插件 " + pluginName
                                    + " 的 source 路径越过 root 目录，已拒绝: " + src
                                    + " -> " + abs + " (root: " + normalizedRoot + ")");
                }
            } else {
                logger.info("marketplace {} 中插件 {} 使用绝对路径 source: {}",
                        marketplaceName, pluginName, abs);
            }
            return abs;
        }
        throw new IllegalArgumentException("marketplace " + marketplaceName
                + " 中未找到插件: " + pluginName);
    }

    /** 尝试 .claude-plugin/marketplace.json → marketplace.json。 */
    private static MarketplaceDefinition parseManifest(Path root) {
        Path[] candidates = {
                root.resolve(".claude-plugin").resolve("marketplace.json"),
                root.resolve("marketplace.json"),
        };
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) continue;
            try {
                return MAPPER.readValue(candidate.toFile(), MarketplaceDefinition.class);
            } catch (IOException e) {
                logger.warn("解析 marketplace 清单失败: {}", candidate, e);
            }
        }
        return null;
    }

    /** 返回一个简单的 Map 快照，供 /plugin marketplace list 展示。 */
    public Map<String, Object> describe() {
        Map<String, Object> out = new HashMap<>();
        marketplaces.forEach((name, path) -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("root", path.toString());
            MarketplaceDefinition def = parseManifest(path);
            if (def != null) {
                entry.put("owner", def.getOwner());
                entry.put("description", def.getDescription());
                entry.put("plugin_count", def.getPlugins() == null ? 0 : def.getPlugins().size());
            } else {
                entry.put("warning", "清单不可用");
            }
            out.put(name, entry);
        });
        return out;
    }
}
