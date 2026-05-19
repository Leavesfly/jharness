package io.leavesfly.jharness.app.bootstrap;

import io.leavesfly.jharness.capability.coordination.TeamRegistry;
import io.leavesfly.jharness.capability.hook.HookEvent;
import io.leavesfly.jharness.capability.hook.HookExecutor;
import io.leavesfly.jharness.capability.hook.HookRegistry;
import io.leavesfly.jharness.capability.permission.PermissionChecker;
import io.leavesfly.jharness.capability.permission.PermissionMode;
import io.leavesfly.jharness.capability.session.SessionSnapshot;
import io.leavesfly.jharness.capability.session.SessionStorage;
import io.leavesfly.jharness.capability.task.BackgroundTaskManager;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.extension.plugins.LoadedPlugin;
import io.leavesfly.jharness.extension.skills.SkillRegistry;
import io.leavesfly.jharness.integration.api.OpenAiApiClient;
import io.leavesfly.jharness.integration.cron.CronRegistry;
import io.leavesfly.jharness.integration.mcp.McpClientManager;
import io.leavesfly.jharness.kernel.engine.MessageCompactionService;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.kernel.engine.TokenEstimator;
import io.leavesfly.jharness.prompt.SystemPromptBuilder;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.ToolRegistry;
import io.leavesfly.jharness.tools.builtin.agent.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * QueryEngine 装配器（4.8 拆分自 JHarnessApplication#buildQueryEngine）。
 *
 * 负责把 Settings + CLI 参数装配为一个可用的 {@link QueryEngine}：
 * 包含 API 客户端、权限检查器、工具注册表、插件、MCP、Hook、压缩、自动保存等。
 */
public final class QueryEngineBuilder {

    private static final Logger logger = LoggerFactory.getLogger(QueryEngineBuilder.class);

    private final Settings settings;
    private final String permissionModeStr;
    private final String resumeSessionIdOrNull;

    public QueryEngineBuilder(Settings settings, String permissionModeStr,
                              String resumeSessionIdOrNull) {
        this.settings = settings;
        this.permissionModeStr = permissionModeStr;
        this.resumeSessionIdOrNull = resumeSessionIdOrNull;
    }

    public QueryEngine build() {
        Path cwd = Paths.get(System.getProperty("user.dir"));

        OpenAiApiClient apiClient = new OpenAiApiClient(
                settings.getBaseUrl(),
                settings.getApiKey(),
                settings.getModel(),
                settings.getMaxTokens(),
                settings.getConnectTimeoutSeconds(),
                settings.getReadTimeoutSeconds(),
                settings.getWriteTimeoutSeconds());

        BackgroundTaskManager taskManager = new BackgroundTaskManager(
                Settings.getDefaultDataDir().resolve("tasks"));
        TeamRegistry teamRegistry = new TeamRegistry();
        SkillRegistry skillRegistry = SkillRegistry.withProject(cwd);
        McpClientManager mcpManager = new McpClientManager();
        CronRegistry cronRegistry = new CronRegistry(Settings.getDefaultDataDir());

        PermissionMode mode;
        try {
            mode = PermissionMode.valueOf(permissionModeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("未知权限模式 '{}', 使用默认模式", permissionModeStr);
            mode = PermissionMode.DEFAULT;
        }
        PermissionChecker permissionChecker = new PermissionChecker(mode);

        for (String tool : settings.getAllowedTools()) {
            permissionChecker.addAllowedTool(tool);
        }
        for (String tool : settings.getDeniedTools()) {
            permissionChecker.addDeniedTool(tool);
        }
        for (var rule : settings.getPathRules()) {
            try {
                Object pattern = rule.get("pattern");
                Object allow = rule.get("allow");
                if (pattern instanceof String p && !p.isBlank()) {
                    boolean isAllow = allow instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(allow));
                    permissionChecker.addPathRule(p, isAllow);
                } else {
                    logger.warn("忽略无效的 pathRule（缺少 pattern 字段）: {}", rule);
                }
            } catch (IllegalArgumentException e) {
                logger.warn("无效的路径规则模式，已忽略: {}", rule, e);
            }
        }
        for (String pattern : settings.getDeniedCommandPatterns()) {
            if (pattern != null && !pattern.isBlank()) {
                permissionChecker.addDeniedCommand(pattern);
            }
        }

        taskManager.setPermissionChecker(permissionChecker);
        mcpManager.setPermissionChecker(permissionChecker);

        HookRegistry hookRegistry = new HookRegistry();
        List<LoadedPlugin> loadedPlugins =
                PluginBootstrap.loadPluginsQuietly(settings, cwd, skillRegistry, hookRegistry);

        PluginBootstrap.registerPluginAgents(teamRegistry, loadedPlugins);
        McpBootstrap.registerMcpServers(mcpManager, settings, loadedPlugins);

        ToolRegistry toolRegistry = ToolRegistry.withDefaults(
                settings, taskManager, teamRegistry, skillRegistry, mcpManager, cronRegistry);

        String basePrompt = "你是 JHarness，一个强大的 AI 编程助手。你可以使用工具来帮助用户完成各种任务。";
        String projectContext = McpBootstrap.loadProjectContext(cwd);
        if (!projectContext.isBlank()) {
            basePrompt = basePrompt + "\n\n## 项目上下文 (CLAUDE.md)\n" + projectContext;
        }
        String systemPrompt = new SystemPromptBuilder(basePrompt)
                .withSkills(skillRegistry)
                .build();

        QueryEngine engine = new QueryEngine(apiClient, toolRegistry, permissionChecker,
                cwd, systemPrompt, settings.getMaxTurns());

        engine.getCostTracker().setModelName(settings.getModel());
        engine.getCostTracker().setDailyBudgetUsd(settings.getDailyBudgetUsd());

        int userBudget = settings.getMessageCompactionTokenBudget();
        int userMaxMessages = settings.getMessageCompactionMaxMessages();
        int sysTokens = TokenEstimator.estimateText(systemPrompt);
        if (userBudget > 0 || userMaxMessages > 0) {
            MessageCompactionService svc = new MessageCompactionService(
                    userMaxMessages > 0 ? userMaxMessages : 20,
                    5,
                    userBudget > 0 ? userBudget : 32_000);
            engine.setCompactionService(svc.withSystemPromptTokens(sysTokens));
        } else {
            engine.setCompactionService(
                    new MessageCompactionService().withSystemPromptTokens(sysTokens));
        }

        if (settings.isAutoSaveSessions()) {
            configureAutoSaveSession(engine, settings, cwd);
        }

        final McpClientManager mcpForClose = mcpManager;
        final CronRegistry cronForClose = cronRegistry;
        final BackgroundTaskManager taskForClose = taskManager;
        final ToolRegistry registryForRefresh = toolRegistry;
        engine.registerCloseHook(() -> {
            try { mcpForClose.close(); } catch (Exception ex) { logger.warn("关闭 MCP 失败", ex); }
            try { cronForClose.close(); } catch (Exception ex) { logger.warn("关闭 Cron 失败", ex); }
            try { taskForClose.shutdown(); } catch (Exception ex) { logger.warn("关闭 Task 失败", ex); }
        });
        engine.setToolRegistry(registryForRefresh);

        mcpForClose.onConnected(() -> {
            try {
                int added = registryForRefresh.refreshMcpTools(mcpForClose);
                if (added > 0) {
                    logger.info("MCP 连接完成后新增 {} 个动态工具", added);
                }
            } catch (Exception ex) {
                logger.warn("刷新 MCP 动态工具失败（忽略）", ex);
            }
        });

        BaseTool<?> agentRaw = toolRegistry.get("agent_spawn");
        if (agentRaw instanceof AgentTool agentTool) {
            agentTool.configureInProcess(apiClient, toolRegistry, permissionChecker);
            logger.info("AgentTool in_process 模式已配置");
        }

        if (hookRegistry.size() > 0) {
            HookExecutor hookExecutor = new HookExecutor(hookRegistry, cwd);
            hookExecutor.setPermissionChecker(permissionChecker);

            String sessionIdForHook = resumeSessionIdOrNull != null && !resumeSessionIdOrNull.isBlank()
                    ? resumeSessionIdOrNull
                    : UUID.randomUUID().toString().substring(0, 8);

            engine.setHookEmitter(hookExecutor, sessionIdForHook);

            try {
                hookExecutor.execute(HookEvent.SESSION_START, Map.of(
                        "session_id", sessionIdForHook,
                        "model", settings.getModel(),
                        "cwd", cwd.toString())).join();
            } catch (Exception e) {
                logger.warn("SESSION_START Hook 执行失败（忽略）", e);
            }

            final HookExecutor finalExecutor = hookExecutor;
            final String finalSessionId = sessionIdForHook;
            engine.registerCloseHook(() -> {
                try {
                    finalExecutor.execute(HookEvent.SESSION_END, Map.of(
                            "session_id", finalSessionId,
                            "model", settings.getModel(),
                            "cwd", cwd.toString())).join();
                } catch (Exception ex) {
                    logger.debug("SESSION_END Hook 执行失败（忽略）: {}", ex.getMessage());
                }
            });
        }

        engine.setLoadedPlugins(loadedPlugins);
        return engine;
    }

    private void configureAutoSaveSession(QueryEngine engine, Settings settings, Path cwd) {
        try {
            Path sessionsDir = Settings.getDefaultDataDir().resolve("sessions");
            SessionStorage storage = new SessionStorage(sessionsDir);
            String sessionId = resumeSessionIdOrNull != null && !resumeSessionIdOrNull.isBlank()
                    ? resumeSessionIdOrNull
                    : UUID.randomUUID().toString().substring(0, 8);
            String modelName = settings.getModel();
            Instant createdAt = Instant.now();
            engine.setSessionPersister(messages -> {
                try {
                    if (messages == null || messages.isEmpty()) return;
                    SessionSnapshot snap = new SessionSnapshot(
                            sessionId,
                            cwd.toString(),
                            modelName,
                            messages,
                            engine.getCostTracker().toUsageSnapshot(),
                            createdAt,
                            /* summary */ null,
                            messages.size());
                    storage.saveSession(snap);
                } catch (Exception e) {
                    logger.debug("自动保存会话失败（忽略）: {}", e.getMessage());
                }
            });
            logger.info("会话自动保存已启用: id={}, dir={}", sessionId, sessionsDir);
        } catch (Exception e) {
            logger.warn("配置会话自动保存失败（忽略）", e);
        }
    }
}
