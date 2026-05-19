package io.leavesfly.jharness.integration.mcp;

import io.leavesfly.jharness.capability.permission.PermissionDecision;
import io.leavesfly.jharness.integration.mcp.session.HttpMcpSession;
import io.leavesfly.jharness.kernel.spi.PermissionGate;
import io.leavesfly.jharness.integration.mcp.session.McpExecutorFactory;
import io.leavesfly.jharness.integration.mcp.session.McpSession;
import io.leavesfly.jharness.integration.mcp.session.StdioMcpSession;
import io.leavesfly.jharness.integration.mcp.types.McpConnectionStatus;
import io.leavesfly.jharness.util.UrlSafetyValidator;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 客户端管理器
 *
 * 管理 MCP 服务器的连接和工具发现。
 * 支持 stdio 和 HTTP 传输方式。
 */
public class McpClientManager {
    private static final Logger logger = LoggerFactory.getLogger(McpClientManager.class);

    private final Map<String, Map<String, Object>> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, McpConnectionStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    /** 有界线程池 + 命名 ThreadFactory，详见 {@link McpExecutorFactory}。 */
    private final ExecutorService executor = McpExecutorFactory.newDefault();
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);
    private final OkHttpClient sharedHttpClient;

    /**
     * 可选的权限闸门（依赖 {@link PermissionGate} SPI 而非具体实现，
     * 由 P0-2 引入以消除 integration → capability 的反向依赖）。
     * 注入后，stdio 类型的 MCP 服务器在 fork 子进程前会先走权限评估，
     * 避免通过 MCP 配置绕过命令黑名单。
     */
    private volatile PermissionGate permissionChecker;

    /**
     * 注入权限闸门，使 stdio MCP 服务器与前台工具共用同一套安全栅栏。
     */
    public void setPermissionChecker(PermissionGate permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    public McpClientManager() {
        this.sharedHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                // SSRF 防护：禁止跟随重定向，避免 302 到内网地址绕过 URL 校验
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    /**
     * 添加服务器配置
     */
    public void addServer(String name, Map<String, Object> config) {
        serverConfigs.put(name, config);
        statuses.put(name, new McpConnectionStatus("pending", 
            (String) config.get("type"), null));
        logger.info("添加 MCP 服务器: {}", name);
    }
    
    /**
     * 连接所有配置的服务器
     *
     * 连接完成后（无论成功/失败）会回调所有通过 {@link #onConnected(Runnable)} 注册的监听器，
     * 便于 ToolRegistry 在 MCP 工具可用后补注册 McpToolAdapter。
     */
    public CompletableFuture<Void> connectAll() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : serverConfigs.entrySet()) {
            futures.add(connectServer(entry.getKey(), entry.getValue()));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, err) -> fireConnectedListeners());
    }

    /** 连接完成监听器列表。CopyOnWriteArrayList 保证并发读写安全。 */
    private final List<Runnable> connectedListeners = new CopyOnWriteArrayList<>();

    /**
     * 注册"连接完成"监听器。{@link #connectAll()} 完成后（无论成功/失败）都会回调。
     * 如果注册时已有连接好的服务器，会立刻触发一次回调，避免竞态导致错过事件。
     */
    public void onConnected(Runnable listener) {
        if (listener == null) return;
        connectedListeners.add(listener);
        // 若已经有 connected 会话，立即补发一次，避免 addServer 在 connectAll 后发生
        boolean hasConnected = statuses.values().stream()
                .anyMatch(s -> "connected".equals(s.getState()));
        if (hasConnected) {
            try {
                listener.run();
            } catch (Exception e) {
                logger.warn("MCP onConnected 监听器执行失败（忽略）", e);
            }
        }
    }

    /** 触发所有"连接完成"监听器，单个监听器异常不影响其它监听器。 */
    private void fireConnectedListeners() {
        for (Runnable r : connectedListeners) {
            try {
                r.run();
            } catch (Exception e) {
                logger.warn("MCP onConnected 监听器执行失败（忽略）", e);
            }
        }
    }
    
    /**
     * 连接单个服务器
     */
    public CompletableFuture<Void> connectServer(String name, Map<String, Object> config) {
        return CompletableFuture.runAsync(() -> {
            try {
                String type = (String) config.get("type");
                if ("stdio".equals(type)) {
                    connectStdio(name, config);
                } else if ("http".equals(type) || "https".equals(type)) {
                    connectHttp(name, config);
                } else {
                    statuses.put(name, new McpConnectionStatus("failed", type, 
                        "不支持的传输类型: " + type));
                }
            } catch (Exception e) {
                logger.error("连接 MCP 服务器失败: {}", name, e);
                statuses.put(name, new McpConnectionStatus("failed", 
                    (String) config.get("type"), e.getMessage()));
            }
        }, executor);
    }
    
    /**
     * 连接 stdio 类型的 MCP 服务器
     */
    @SuppressWarnings("unchecked")
    private void connectStdio(String name, Map<String, Object> config) throws Exception {
        String command = (String) config.get("command");
        List<String> args = (List<String>) config.getOrDefault("args", Collections.emptyList());
        Map<String, String> env = (Map<String, String>) config.get("env");
        String cwd = (String) config.get("cwd");

        // 对 stdio MCP 的启动命令走权限闸门（P0-2 后依赖 PermissionGate SPI）。
        // 拼成完整命令行用于黑名单匹配，防止通过 mcpServers 配置写入 "rm -rf /" 类命令
        // 绕过 bash 工具黑名单。
        PermissionGate checker = permissionChecker;
        if (checker != null) {
            StringBuilder full = new StringBuilder(command == null ? "" : command);
            for (String a : args) {
                full.append(' ').append(a);
            }
            PermissionDecision decision = checker.evaluate("bash", false, null, full.toString());
            if (decision != null && !decision.isAllowed() && !decision.isRequiresConfirmation()) {
                logger.warn("MCP stdio 服务器启动被权限拒绝: server={}, reason={}",
                        name, decision.getReason());
                throw new SecurityException("MCP stdio 命令被权限拒绝: " + decision.getReason());
            }
        }

        ProcessBuilder pb = new ProcessBuilder();
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);
        pb.command(cmd);

        if (env != null) {
            pb.environment().putAll(env);
        }
        if (cwd != null) {
            pb.directory(new java.io.File(cwd));
        }

        pb.redirectErrorStream(true);
        Process process = pb.start();

        try {
            McpSession session = new StdioMcpSession(process, name, requestIdCounter);
            session.initialize();

            List<McpConnectionStatus.McpToolInfo> tools = session.listTools();
            List<McpConnectionStatus.McpResourceInfo> resources = session.listResources();


            sessions.put(name, session);
            statuses.put(name, new McpConnectionStatus("connected", "stdio", null,
                env != null && !env.isEmpty(), tools, resources));
            
            logger.info("MCP 服务器已连接: {}, 工具数: {}", name, tools.size());
        } catch (Exception e) {
            // 初始化失败时清理进程，防止泄漏
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            throw e;
        }
    }
    
    /**
     * 连接 HTTP 类型的 MCP 服务器。
     *
     * 安全约束（S-4）：在建立会话前必须通过 UrlSafetyValidator：
     *   - 仅允许 http/https；
     *   - 拒绝解析到回环/链路本地（含 169.254.169.254 云元数据）/站点本地/多播等地址。
     */
    @SuppressWarnings("unchecked")
    private void connectHttp(String name, Map<String, Object> config) throws Exception {
        String url = (String) config.get("url");
        Map<String, String> headers = (Map<String, String>) config.get("headers");

        String safetyError = UrlSafetyValidator.validate(url);
        if (safetyError != null) {
            logger.warn("拒绝不安全的 MCP HTTP URL: server={}, reason={}", name, safetyError);
            throw new IllegalArgumentException("MCP HTTP 服务器 URL 不安全: " + safetyError);
        }

        HttpMcpSession session = new HttpMcpSession(sharedHttpClient, url, headers, name, requestIdCounter);
        session.initialize();

        List<McpConnectionStatus.McpToolInfo> tools = session.listTools();
        List<McpConnectionStatus.McpResourceInfo> resources = session.listResources();

        sessions.put(name, session);
        statuses.put(name, new McpConnectionStatus("connected", "http", null,
                headers != null && !headers.isEmpty(), tools, resources));

        logger.info("MCP HTTP 服务器已连接: {}, 工具数: {}", name, tools.size());
    }
    
    /**
     * 列出所有工具
     */
    public List<McpConnectionStatus.McpToolInfo> listTools() {
        List<McpConnectionStatus.McpToolInfo> allTools = new ArrayList<>();
        for (McpConnectionStatus status : statuses.values()) {
            if ("connected".equals(status.getState())) {
                allTools.addAll(status.getTools());
            }
        }
        return allTools;
    }
    
    /**
     * 列出所有资源
     */
    public List<McpConnectionStatus.McpResourceInfo> listResources() {
        List<McpConnectionStatus.McpResourceInfo> allResources = new ArrayList<>();
        for (McpConnectionStatus status : statuses.values()) {
            if ("connected".equals(status.getState())) {
                allResources.addAll(status.getResources());
            }
        }
        return allResources;
    }
    
    /**
     * 调用 MCP 工具
     */
    public CompletableFuture<String> callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpSession session = sessions.get(serverName);
        if (session == null) {
            return CompletableFuture.completedFuture("错误: 服务器未连接: " + serverName);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return session.callTool(toolName, arguments);
            } catch (Exception e) {
                logger.error("工具调用失败: {}.{}", serverName, toolName, e);
                return "工具调用失败: " + e.getMessage();
            }
        }, executor);
    }
    
    /**
     * 读取 MCP 资源
     */
    public CompletableFuture<String> readResource(String serverName, String uri) {
        McpSession session = sessions.get(serverName);
        if (session == null) {
            return CompletableFuture.completedFuture("错误: 服务器未连接: " + serverName);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return session.readResource(uri);
            } catch (Exception e) {
                logger.error("资源读取失败: {}:{}", serverName, uri, e);
                return "资源读取失败: " + e.getMessage();
            }
        }, executor);
    }
    
    /**
     * 获取连接状态
     */
    public McpConnectionStatus getStatus(String serverName) {
        return statuses.getOrDefault(serverName, 
            new McpConnectionStatus("disconnected", null, null));
    }
    
    /**
     * 列出所有服务器
     */
    public List<String> listServers() {
        return new ArrayList<>(serverConfigs.keySet());
    }
    
    /**
     * 列出所有连接状态
     */
    public List<McpConnectionStatus> listStatuses() {
        List<McpConnectionStatus> result = new ArrayList<>();
        for (String name : serverConfigs.keySet().stream().sorted().toList()) {
            result.add(statuses.get(name));
        }
        return result;
    }
    
    /**
     * 重新连接所有服务器
     */
    public CompletableFuture<Void> reconnectAll() {
        return CompletableFuture.runAsync(() -> {
            close();
            for (Map.Entry<String, Map<String, Object>> entry : serverConfigs.entrySet()) {
                try {
                    connectServer(entry.getKey(), entry.getValue()).get();
                } catch (Exception e) {
                    logger.error("重新连接失败: {}", entry.getKey(), e);
                }
            }
        }, executor);
    }
    
    /**
     * 更新服务器配置
     *
     * 当配置为 HTTP/HTTPS 类型时，这里提前做一次 URL 安全校验，
     * 避免后续重连路径成为 SSRF 注入入口（S-4 补强）。
     */
    public void updateServerConfig(String name, Map<String, Object> config) {
        String type = config == null ? null : (String) config.get("type");
        if ("http".equals(type) || "https".equals(type)) {
            String url = (String) config.get("url");
            String err = UrlSafetyValidator.validate(url);
            if (err != null) {
                throw new IllegalArgumentException("MCP HTTP 服务器 URL 不安全: " + err);
            }
        }
        serverConfigs.put(name, config);
    }
    
    /**
     * 关闭所有连接
     *
     * 关闭顺序：先停止 executor（等待异步任务结束），再关闭 sessions，
     * 防止异步任务在 sessions 已清空后继续访问导致 NPE。
     */
    public void close() {
        // 第一步：停止接受新任务，等待已提交的异步任务完成
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
                // 等待被中断的任务响应取消
                executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 第二步：所有异步任务已结束，安全关闭 sessions
        for (McpSession session : sessions.values()) {
            try {
                session.close();
            } catch (Exception e) {
                logger.error("关闭会话失败", e);
            }
        }
        sessions.clear();
        statuses.clear();

        // 第三步：关闭共享 HTTP 客户端，释放连接池和线程
        sharedHttpClient.dispatcher().executorService().shutdown();
        sharedHttpClient.connectionPool().evictAll();
    }
}
