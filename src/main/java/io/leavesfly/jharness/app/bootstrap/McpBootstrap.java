package io.leavesfly.jharness.app.bootstrap;

import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.extension.plugins.LoadedPlugin;
import io.leavesfly.jharness.integration.mcp.McpClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * MCP 注入与项目上下文加载工具集（4.8 拆分自 JHarnessApplication）。
 */
public final class McpBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(McpBootstrap.class);

    private McpBootstrap() {}

    /**
     * 把 {@link Settings#getMcpServers()} 与插件提供的 MCP 配置合并注入到 {@link McpClientManager}，
     * 并异步发起连接。连接耗时不阻塞主流程，连上后由 ToolRegistry 的 MCP 适配器注册动态工具。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerMcpServers(McpClientManager mcpManager, Settings settings,
                                          List<LoadedPlugin> plugins) {
        int count = 0;
        try {
            Map<String, Object> rootMcp = settings.getMcpServers();
            if (rootMcp != null) {
                for (Map.Entry<String, Object> e : rootMcp.entrySet()) {
                    if (e.getValue() instanceof Map cfg && !cfg.isEmpty()) {
                        mcpManager.addServer(e.getKey(), (Map<String, Object>) cfg);
                        count++;
                    }
                }
            }
            for (LoadedPlugin plugin : plugins) {
                Map<String, Object> pluginMcp = plugin.getMcpServers();
                if (pluginMcp == null || pluginMcp.isEmpty()) {
                    continue;
                }
                Object servers = pluginMcp.getOrDefault("mcpServers", pluginMcp);
                if (servers instanceof Map serverMap) {
                    for (Object entry : serverMap.entrySet()) {
                        Map.Entry<String, Object> e = (Map.Entry<String, Object>) entry;
                        if (e.getValue() instanceof Map cfg && !cfg.isEmpty()) {
                            mcpManager.addServer(plugin.getName() + ":" + e.getKey(),
                                    (Map<String, Object>) cfg);
                            count++;
                        }
                    }
                }
            }
            if (count > 0) {
                logger.info("注册 {} 个 MCP 服务器，后台异步连接中...", count);
                mcpManager.connectAll().whenComplete((v, err) -> {
                    if (err != null) {
                        logger.warn("部分 MCP 服务器连接失败（忽略）: {}", err.getMessage());
                    } else {
                        logger.info("所有 MCP 服务器连接流程已完成");
                    }
                });
            }
        } catch (Exception e) {
            logger.warn("注册 MCP 服务器时出错（忽略并继续）", e);
        }
    }

    /**
     * 读取项目根下的 CLAUDE.md 文件（若存在且大小合理）。
     * 限制：最多读取 128KB，避免把超大文档塞进 system prompt 炸预算。
     */
    public static String loadProjectContext(Path cwd) {
        try {
            Path contextFile = cwd.resolve("CLAUDE.md");
            if (!Files.isRegularFile(contextFile)) {
                return "";
            }
            long size = Files.size(contextFile);
            if (size > 128L * 1024L) {
                byte[] bytes = Files.readAllBytes(contextFile);
                return new String(bytes, 0, (int) Math.min(bytes.length, 128 * 1024),
                        StandardCharsets.UTF_8) + "\n...[CLAUDE.md 已截断]";
            }
            return Files.readString(contextFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
