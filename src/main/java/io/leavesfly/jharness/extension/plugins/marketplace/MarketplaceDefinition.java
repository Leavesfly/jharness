package io.leavesfly.jharness.extension.plugins.marketplace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 【P2】Marketplace 清单（对齐 Claude Code 的 {@code .claude-plugin/marketplace.json}）。
 *
 * <p>结构示例：
 * <pre>{@code
 * {
 *   "name": "my-marketplace",
 *   "owner": "alice",
 *   "description": "my plugin collection",
 *   "plugins": [
 *     { "name": "git-helper", "source": "./git-helper" },
 *     { "name": "db-helper",  "source": "../shared/db-helper" }
 *   ]
 * }
 * }</pre>
 *
 * 未识别字段（如 metadata / version / homepage 等）通过 {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * 静默忽略，保证 Claude Code 现成的 marketplace.json 能零改动被吃进来。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MarketplaceDefinition {

    private String name;
    private String owner;
    private String description;
    private List<PluginRef> plugins = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<PluginRef> getPlugins() { return plugins; }
    public void setPlugins(List<PluginRef> plugins) {
        this.plugins = plugins == null ? new ArrayList<>() : plugins;
    }

    /**
     * marketplace 中的单个插件引用。{@code source} 可以是：
     * <ul>
     *   <li>相对目录（相对 marketplace 根目录）</li>
     *   <li>绝对本地路径</li>
     *   <li>{@code git+https://...} 开头的 git 仓库（需另行实现 clone）</li>
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PluginRef {
        private String name;
        private String source;
        private String description;
        private String version;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }
}
