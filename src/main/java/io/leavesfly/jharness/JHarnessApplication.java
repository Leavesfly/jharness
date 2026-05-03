package io.leavesfly.jharness;

import io.leavesfly.jharness.agent.hooks.HookEvent;
import io.leavesfly.jharness.agent.hooks.HookExecutor;
import io.leavesfly.jharness.agent.hooks.HookRegistry;
import io.leavesfly.jharness.extension.plugins.LoadedPlugin;
import io.leavesfly.jharness.extension.plugins.PluginLoader;
import io.leavesfly.jharness.integration.api.OpenAiApiClient;
import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.core.SettingsBootstrap;
import io.leavesfly.jharness.agent.coordinator.TeamRegistry;
import io.leavesfly.jharness.core.engine.QueryEngine;
import io.leavesfly.jharness.core.engine.model.ConversationMessage;
import io.leavesfly.jharness.core.engine.stream.AssistantTextDelta;
import io.leavesfly.jharness.core.engine.stream.AssistantTurnComplete;
import io.leavesfly.jharness.core.engine.stream.StreamEvent;
import io.leavesfly.jharness.core.engine.stream.ToolExecutionStarted;
import io.leavesfly.jharness.core.engine.stream.ToolExecutionCompleted;
import io.leavesfly.jharness.core.engine.stream.UsageReport;
import io.leavesfly.jharness.integration.mcp.McpClientManager;
import io.leavesfly.jharness.session.permissions.PermissionChecker;
import io.leavesfly.jharness.session.permissions.PermissionMode;
import io.leavesfly.jharness.prompts.SystemPromptBuilder;
import io.leavesfly.jharness.integration.CronRegistry;
import io.leavesfly.jharness.extension.skills.SkillRegistry;
import io.leavesfly.jharness.agent.tasks.BackgroundTaskManager;
import io.leavesfly.jharness.session.sessions.SessionSnapshot;
import io.leavesfly.jharness.session.sessions.SessionStorage;
import io.leavesfly.jharness.tools.AgentTool;
import io.leavesfly.jharness.tools.BaseTool;
import io.leavesfly.jharness.tools.ToolRegistry;
import io.leavesfly.jharness.command.commands.CommandRegistry;
import io.leavesfly.jharness.ui.tui.ConsoleInteractiveSession;
import io.leavesfly.jharness.ui.tui.TerminalUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * JHarness 应用程序主入口
 *
 * 基于 Picocli 框架实现的命令行界面，支持交互式模式和单次查询模式。
 *
 * 使用示例：
 * <pre>
 * # 交互式模式
 * jharness
 *
 * # 单次查询模式
 * jharness -p "解释这段代码"
 *
 * # 查看帮助
 * jharness --help
 * </pre>
 */
@Command(name = "jharness",
         mixinStandardHelpOptions = true,
         version = "jharness 0.1.0",
         description = "JHarness - Java 实现的轻量级 AI 智能体框架")
public class JHarnessApplication implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(JHarnessApplication.class);

    @Option(names = {"-p", "--print"},
            description = "单次查询模式：直接执行提示并输出结果")
    private String printPrompt;

    @Option(names = {"-m", "--model"},
            description = "指定使用的 LLM 模型")
    private String model;

    @Option(names = {"-d", "--debug"},
            description = "启用调试模式")
    private boolean debug;

    @Option(names = {"-c", "--continue"},
            description = "继续上一次会话")
    private boolean continueSession;

    @Option(names = {"-r", "--resume"},
            description = "恢复指定会话 ID")
    private String resumeSessionId;

    @Option(names = {"--permission-mode"},
            description = "权限模式：default, full_auto, plan",
            defaultValue = "default")
    private String permissionMode;

    @Option(names = {"--max-turns"},
            description = "最大对话轮次",
            defaultValue = "8")
    private int maxTurns;

    @Option(names = {"--output-format"},
            description = "输出格式：text, json, stream-json",
            defaultValue = "text")
    private String outputFormat;

    @Option(names = {"--tui"},
            description = "启用终端用户界面 (TUI)")
    private boolean enableTUI;

    /**
     * 应用程序入口点
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new JHarnessApplication()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (debug) {
            logger.debug("调试模式已启用");
        }

        logger.info("JHarness 启动");
        logger.info("权限模式: {}", permissionMode);
        logger.info("最大轮次: {}", maxTurns);

        // 【开箱即用】首次启动时从 classpath 释放默认配置与默认插件到 ~/.jharness，
        // 保证无任何手动配置也能跑起来。幂等：已存在则跳过。
        SettingsBootstrap.seedIfAbsent();

        // 加载配置
        Settings settings = Settings.load();
        applyCliOverrides(settings);

        logger.info("模型: {}", settings.getModel());

        // P-07 修复：项目已经迁到 OpenAI 兼容协议，API Key 的优先环境变量应是 OPENAI_API_KEY，
        // 同时保留 ANTHROPIC_API_KEY 兼容项
        // 【开箱即用】如果 baseUrl 指向本地端点（Ollama 等），允许 API Key 为空，由 OpenAiApiClient
        // 自动用占位符启动；仅当 baseUrl 指向远程端点且未配置 Key 时才报错退出。
        if (settings.getApiKey() == null || settings.getApiKey().isBlank()) {
            if (OpenAiApiClient.isLocalEndpoint(settings.getBaseUrl())) {
                logger.info("未配置 API Key，但检测到本地端点 {}，将以 Ollama 模式启动", settings.getBaseUrl());
            } else {
                String msg = "未配置 API Key，请设置环境变量 OPENAI_API_KEY（或兼容的 ANTHROPIC_API_KEY），"
                        + "或在 ~/.jharness/settings.json 中配置 apiKey 字段；"
                        + "或将 baseUrl 指向本地端点（如 http://localhost:11434/v1，配合 Ollama 使用）";
                logger.error(msg);
                System.err.println("错误: " + msg + "。");
                return 1;
            }
        }

        if (printPrompt != null && !printPrompt.isEmpty()) {
            logger.info("单次查询模式: {}", printPrompt);
            return runPrintMode(settings);
        } else {
            logger.info("交互式模式");
            return runInteractiveMode(settings);
        }
    }

    /**
     * 尝试恢复历史会话到 {@link QueryEngine}。
     *
     * 支持两种入口：
     * <ul>
     *   <li>{@code -r/--resume <sessionId>}：按指定 ID 恢复；</li>
     *   <li>{@code -c/--continue}：默认恢复最近一次会话；若无历史会话则静默跳过。</li>
     * </ul>
     *
     * 恢复成功时会把消息历史注入到 engine 并打印一条提示；恢复失败不阻断主流程。
     *
     * @return true 表示本次调用恢复了会话（仅用于日志），false 表示未恢复或失败
     */
    private boolean tryRestoreSession(QueryEngine engine) {
        if (resumeSessionId == null && !continueSession) {
            return false;
        }
        try {
            Path sessionsDir = Settings.getDefaultDataDir().resolve("sessions");
            SessionStorage storage = new SessionStorage(sessionsDir);
            SessionSnapshot snapshot;
            String targetId;
            if (resumeSessionId != null && !resumeSessionId.isBlank()) {
                targetId = resumeSessionId;
                snapshot = storage.loadSession(targetId);
            } else {
                // --continue：取最近一次会话
                List<SessionSnapshot> recent = storage.listSessions(1);
                if (recent.isEmpty()) {
                    System.out.println("提示: 暂无历史会话可继续（目录: " + sessionsDir + "）");
                    return false;
                }
                snapshot = recent.get(0);
                targetId = snapshot.getSessionId();
            }
            if (snapshot == null) {
                System.err.println("错误: 未找到会话 " + targetId + "（目录: " + sessionsDir + "）");
                return false;
            }
            List<ConversationMessage> messages = snapshot.getMessages();
            if (messages == null || messages.isEmpty()) {
                System.out.println("提示: 会话 " + targetId + " 为空，跳过恢复");
                return false;
            }
            engine.loadMessages(messages);
            System.out.printf("📂 已恢复会话 %s（消息 %d 条，模型 %s）%n",
                    targetId, messages.size(), snapshot.getModel());
            return true;
        } catch (Exception e) {
            logger.warn("恢复会话失败（忽略并继续）", e);
            System.err.println("提示: 恢复会话失败: " + e.getMessage() + "（忽略并继续）");
            return false;
        }
    }

    /**
     * 将 CLI 参数覆盖到 Settings 中
     */
    private void applyCliOverrides(Settings settings) {
        if (model != null && !model.isBlank()) {
            settings.setModel(model);
        }
        settings.setMaxTurns(maxTurns);
        settings.setPermissionMode(permissionMode);
    }

    /**
     * 构建 QueryEngine 实例（组装完整依赖链）。
     *
     * 本次改造（P-03/P-04/P-08）：
     * <ul>
     *   <li>P-03：从 {@code settings.getMcpServers()} + 已加载插件的 {@code mcpServers} 中收集 MCP 服务器
     *       配置，注入到 {@link McpClientManager} 并 {@code connectAll()} 异步启动；动态 MCP 工具在连上后
     *       由 {@link ToolRegistry} 的 MCP 适配器自动发现（改造后顺序：先连 MCP 再注册工具）；</li>
     *   <li>P-04：调用 {@link PluginLoader#loadPlugins} 加载用户级 + 项目级插件，把插件里的 skills / hooks /
     *       mcpServers 合并到对应注册表，真正让插件/Hook 系统生效；</li>
     *   <li>P-08：读取项目根下的 {@code CLAUDE.md}（若存在）并拼接到系统提示词，满足 wiki 宣传的
     *       "项目上下文文件"能力。</li>
     * </ul>
     */
    private QueryEngine buildQueryEngine(Settings settings) {
        Path cwd = Paths.get(System.getProperty("user.dir"));

        // 【改造】使用 Settings 中新增的超时字段，避免连接慢/读取慢的环境下被 300s 硬编码坑到
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
            mode = PermissionMode.valueOf(permissionMode.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("未知权限模式 '{}', 使用默认模式", permissionMode);
            mode = PermissionMode.DEFAULT;
        }
        PermissionChecker permissionChecker = new PermissionChecker(mode);

        for (String tool : settings.getAllowedTools()) {
            permissionChecker.addAllowedTool(tool);
        }
        for (String tool : settings.getDeniedTools()) {
            permissionChecker.addDeniedTool(tool);
        }
        // FP-1：把配置文件里的 pathRules / deniedCommandPatterns 装配到 PermissionChecker，
        // 否则路径规则 / 命令黑名单功能虽然实现了但从来不会被触发。
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

        // FP-3：把 PermissionChecker 注入到后台入口，使 BackgroundTaskManager / McpClientManager
        // 与前台 bash 工具共享同一套安全栅栏，避免黑名单/规则通过后台通道被绕过。
        taskManager.setPermissionChecker(permissionChecker);
        mcpManager.setPermissionChecker(permissionChecker);

        // P-04：加载用户级 + 项目级插件，让 Skill / Hook / MCP 扩展系统真正生效
        HookRegistry hookRegistry = new HookRegistry();
        List<LoadedPlugin> loadedPlugins = loadPluginsQuietly(settings, cwd, skillRegistry, hookRegistry);

        // 【P0-wire-up】把 plugin 提供的 subagent 注册到 teamRegistry（每个 agent 一个同名 team，
        // 供 AgentOrchestrator / /agents 命令使用）。
        registerPluginAgents(teamRegistry, loadedPlugins);

        // P-03：把 Settings.mcpServers 和插件里的 mcpServers 合并注入到 McpClientManager；
        // 连接成功后再注册 ToolRegistry，保证动态 MCP 工具被一起注册进去。
        registerMcpServers(mcpManager, settings, loadedPlugins);

        ToolRegistry toolRegistry = ToolRegistry.withDefaults(
                settings, taskManager, teamRegistry, skillRegistry, mcpManager, cronRegistry);

        // P-08：若项目根有 CLAUDE.md，则把其内容拼接到系统提示词
        String basePrompt = "你是 JHarness，一个强大的 AI 编程助手。你可以使用工具来帮助用户完成各种任务。";
        String projectContext = loadProjectContext(cwd);
        if (!projectContext.isBlank()) {
            basePrompt = basePrompt + "\n\n## 项目上下文 (CLAUDE.md)\n" + projectContext;
        }
        String systemPrompt = new SystemPromptBuilder(basePrompt)
                .withSkills(skillRegistry)
                .build();

        QueryEngine engine = new QueryEngine(apiClient, toolRegistry, permissionChecker,
                cwd, systemPrompt, settings.getMaxTurns());

        // F-P0-5：把当前模型名注入 CostTracker，让价格表查询生效
        engine.getCostTracker().setModelName(settings.getModel());
        // 【新增】把 Settings 中的日预算注入 CostTracker，超限时 addUsage 会抛 BudgetExceededException
        engine.getCostTracker().setDailyBudgetUsd(settings.getDailyBudgetUsd());

        // 【新增】根据 Settings 配置消息压缩参数，并把 systemPrompt 的 token 提前扣到预算里，
        // 避免超长 CLAUDE.md 导致压缩触发太晚，把 API 上下文直接打爆。
        int userBudget = settings.getMessageCompactionTokenBudget();
        int userMaxMessages = settings.getMessageCompactionMaxMessages();
        if (userBudget > 0 || userMaxMessages > 0) {
            io.leavesfly.jharness.core.engine.MessageCompactionService svc =
                    new io.leavesfly.jharness.core.engine.MessageCompactionService(
                            userMaxMessages > 0 ? userMaxMessages : 20,
                            5,
                            userBudget > 0 ? userBudget : 32_000);
            int sysTokens = io.leavesfly.jharness.core.engine.TokenEstimator.estimateText(systemPrompt);
            engine.setCompactionService(svc.withSystemPromptTokens(sysTokens));
        } else {
            int sysTokens = io.leavesfly.jharness.core.engine.TokenEstimator.estimateText(systemPrompt);
            engine.setCompactionService(
                    new io.leavesfly.jharness.core.engine.MessageCompactionService()
                            .withSystemPromptTokens(sysTokens));
        }

        // 【新增】配置会话自动保存。对每一轮消息变更都做一次快照落盘，
        // 防止会话进行中 JVM 异常退出导致历史丢失。
        if (settings.isAutoSaveSessions()) {
            configureAutoSaveSession(engine, settings, cwd);
        }

        // 【新增】把后台资源（MCP / Cron / Task / Hook）的生命周期挂到 engine 上，
        // engine.close() 时会一起回收，避免单测或脚本调用时线程泄漏。
        final McpClientManager mcpForClose = mcpManager;
        final CronRegistry cronForClose = cronRegistry;
        final BackgroundTaskManager taskForClose = taskManager;
        final ToolRegistry registryForRefresh = toolRegistry;
        engine.registerCloseHook(() -> {
            try { mcpForClose.close(); } catch (Exception ex) { logger.warn("关闭 MCP 失败", ex); }
            try { cronForClose.close(); } catch (Exception ex) { logger.warn("关闭 Cron 失败", ex); }
            try { taskForClose.shutdown(); } catch (Exception ex) { logger.warn("关闭 Task 失败", ex); }
        });

        // 【新增】把 toolRegistry 暴露给下游（TUI 命令注册/自动保存/MCP 刷新），
        // 同时在 MCP 连接完成后刷新一次动态工具。由于 connectAll 是异步的，
        // 首次 withDefaults 时可能工具列表还是空的，需要在连上后补注册。
        engine.registerCloseHook(() -> {/* 占位：toolRegistry 本身不持有线程资源 */});
        // 把 toolRegistry 也记录到 engine，供交互 UI 使用
        engine.setToolRegistry(registryForRefresh);

        // 异步刷新 MCP 动态工具（connectAll 已在 registerMcpServers 中触发）
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

        // F-P1-1：为 AgentTool 注入共享资源，启用 in_process 模式
        BaseTool<?> agentRaw = toolRegistry.get("agent_spawn");
        if (agentRaw instanceof AgentTool agentTool) {
            agentTool.configureInProcess(apiClient, toolRegistry, permissionChecker);
            logger.info("AgentTool in_process 模式已配置");
        }

        // P-04：Hook 执行器共享安全栅栏，确保 Hook 里 fork 子进程也走同一套权限评估。
        // 【Hook 发射点装配】只有在注册表非空时才构造 HookExecutor 并注入到 engine，避免无 Hook
        // 场景下的反射开销。hookExecutor 生命周期绑定到 engine（close hook 里不需要显式释放，
        // 因为它本身不持有后台线程 — 所有子进程/HTTP 请求都是临时的）。
        if (hookRegistry.size() > 0) {
            HookExecutor hookExecutor = new HookExecutor(hookRegistry, cwd);
            hookExecutor.setPermissionChecker(permissionChecker);

            // 生成一次性的进程级 session ID，供所有 Hook payload 使用
            String sessionIdForHook = resumeSessionId != null && !resumeSessionId.isBlank()
                    ? resumeSessionId
                    : java.util.UUID.randomUUID().toString().substring(0, 8);

            // 注入到 engine：发 USER_PROMPT_SUBMIT / STOP
            engine.setHookEmitter(hookExecutor, sessionIdForHook);

            // SESSION_START：尽力而为，失败不阻断启动
            try {
                hookExecutor.execute(HookEvent.SESSION_START, Map.of(
                        "session_id", sessionIdForHook,
                        "model", settings.getModel(),
                        "cwd", cwd.toString())).join();
            } catch (Exception e) {
                logger.warn("SESSION_START Hook 执行失败（忽略）", e);
            }

            // 把 SESSION_END 挂到 engine 的 close hook 上，保证单次 / 交互 / 异常退出时都能发射
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

        // 【P0-wire-up】把已加载插件列表挂到 engine 上，供 buildFullCommandRegistry 在 TUI / 交互模式
        // 构造完整 CommandRegistry 时读取，用于注册 plugin slash commands（避免重复加载）。
        engine.setLoadedPlugins(loadedPlugins);

        return engine;
    }

    /**
     * 【新增】为 {@link QueryEngine} 配置会话自动保存钩子：每当消息变更，把快照落到
     * {@code ~/.jharness/sessions/session-&lt;id&gt;.json}。失败仅记 debug，不影响主流程。
     *
     * <p>会话 ID 策略：本次进程生成一次随机 ID，同一进程内多轮交互共享；
     * 下次进程重启时可通过 {@code --continue} 或 {@code --resume &lt;id&gt;} 恢复。
     */
    private void configureAutoSaveSession(QueryEngine engine, Settings settings, Path cwd) {
        try {
            Path sessionsDir = Settings.getDefaultDataDir().resolve("sessions");
            SessionStorage storage = new SessionStorage(sessionsDir);
            String sessionId = resumeSessionId != null && !resumeSessionId.isBlank()
                    ? resumeSessionId
                    : java.util.UUID.randomUUID().toString().substring(0, 8);
            String modelName = settings.getModel();
            java.time.Instant createdAt = java.time.Instant.now();
            engine.setSessionPersister(messages -> {
                try {
                    if (messages == null || messages.isEmpty()) {
                        return;
                    }
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

    /**
     * 把 {@link Settings#getMcpServers()} 与插件提供的 MCP 配置合并注入到 {@link McpClientManager}，
     * 并异步发起连接。连接耗时不阻塞主流程，连上后由 ToolRegistry 的 MCP 适配器注册动态工具。
     *
     * 静默策略：单个服务器连接失败只记日志，不抛出，避免一个坏配置拖垮整个启动流程。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerMcpServers(McpClientManager mcpManager, Settings settings,
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
                // 异步连接，不阻塞主流程；连上后动态工具通过 McpToolAdapter 被自动装配
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
     * 加载用户级 + 项目级插件，并把插件携带的 skills / hooks 合并到对应注册表。
     *
     * @return 已加载（且 enabled）的插件列表，供后续提取 mcpServers 使用
     */
    private List<LoadedPlugin> loadPluginsQuietly(Settings settings, Path cwd,
                                                  SkillRegistry skillRegistry,
                                                  HookRegistry hookRegistry) {
        try {
            List<LoadedPlugin> plugins = PluginLoader.loadPlugins(settings, cwd);
            int totalCommands = 0;
            int totalAgents = 0;
            for (LoadedPlugin plugin : plugins) {
                if (!plugin.isEnabled()) {
                    continue;
                }
                // 合并插件技能到 SkillRegistry
                plugin.getSkills().forEach(skillRegistry::register);
                // 合并插件 Hook 到 HookRegistry（event 名 -> HookEvent 枚举映射）
                plugin.getHooks().forEach((eventName, defs) -> {
                    HookEvent ev = mapHookEvent(eventName);
                    if (ev != null && defs != null) {
                        defs.forEach(def -> hookRegistry.register(ev, def));
                    } else {
                        logger.warn("插件 {} 声明了未知 Hook 事件: {}", plugin.getName(), eventName);
                    }
                });
                totalCommands += plugin.getCommandPrompts().size();
                totalAgents += plugin.getAgentDefs().size();
            }
            logger.info(
                    "插件系统: 加载 {} 个插件, 合并后技能 {} 个, Hook {} 个, 插件 slash commands {} 个, 插件 agents {} 个",
                    plugins.size(), skillRegistry.getAllSkills().size(), hookRegistry.size(),
                    totalCommands, totalAgents);
            return plugins;
        } catch (Exception e) {
            logger.warn("加载插件失败（忽略并继续）", e);
            return List.of();
        }
    }

    /**
     * 【装配】把插件提供的 slash commands 合并注入到 CommandRegistry。
     *
     * <p>对齐 Claude Code：plugin 的 {@code commands/*.md} 会变成 {@code /xxx} 形式的命令，
     * 正文作为 prompt 模板（支持 $ARGUMENTS / {{args}} / $1..$9 占位符）。
     *
     * <p>冲突策略：若命令名与内置命令冲突，记录 warn 并跳过插件命令，避免静默覆盖内置行为。
     */
    private static int registerPluginCommands(CommandRegistry registry, List<LoadedPlugin> plugins) {
        if (registry == null || plugins == null || plugins.isEmpty()) return 0;
        int added = 0;
        for (LoadedPlugin plugin : plugins) {
            if (!plugin.isEnabled()) continue;
            for (io.leavesfly.jharness.extension.skills.SkillDefinition def : plugin.getCommandPrompts()) {
                if (def == null || def.getName() == null || def.getName().isBlank()) continue;
                if (registry.hasCommand(def.getName())) {
                    logger.warn("插件 {} 的命令 /{} 与已注册命令冲突，跳过", plugin.getName(), def.getName());
                    continue;
                }
                registry.register(io.leavesfly.jharness.command.commands.PluginSlashCommand
                        .fromSkill(def, plugin.getName()));
                added++;
            }
        }
        if (added > 0) {
            logger.info("已注册 {} 个插件 slash command", added);
        }
        return added;
    }

    /**
     * 【装配】把插件提供的 subagent 定义接入 teamRegistry：
     * 为每个 agent 建一个同名团队，metadata 上挂 plugin 名 / description / system prompt，
     * 后续由 /agents 命令 或 AgentOrchestrator 使用时可读取。
     */
    private static int registerPluginAgents(
            io.leavesfly.jharness.agent.coordinator.TeamRegistry teamRegistry,
            List<LoadedPlugin> plugins) {
        if (teamRegistry == null || plugins == null || plugins.isEmpty()) return 0;
        int added = 0;
        for (LoadedPlugin plugin : plugins) {
            if (!plugin.isEnabled()) continue;
            for (io.leavesfly.jharness.extension.skills.SkillDefinition def : plugin.getAgentDefs()) {
                if (def == null || def.getName() == null || def.getName().isBlank()) continue;
                if (teamRegistry.getTeam(def.getName()) != null) {
                    logger.warn("插件 {} 的 agent {} 与已存在团队重名，跳过", plugin.getName(), def.getName());
                    continue;
                }
                io.leavesfly.jharness.agent.coordinator.TeamRecord team =
                        teamRegistry.createTeam(def.getName(), def.getDescription());
                team.setMetadata("source", "plugin");
                team.setMetadata("plugin", plugin.getName());
                team.setMetadata("systemPrompt", def.getContent());
                added++;
            }
        }
        if (added > 0) {
            logger.info("已注册 {} 个插件 subagent 到 TeamRegistry", added);
        }
        return added;
    }

    /**
     * 把字符串形式的 Hook 事件名映射到枚举。不识别时返回 null。
     */
    private static HookEvent mapHookEvent(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim().toLowerCase().replace('-', '_');
        for (HookEvent ev : HookEvent.values()) {
            if (ev.name().toLowerCase().equals(normalized)
                    || ev.getValue().equalsIgnoreCase(name.trim())) {
                return ev;
            }
        }
        return null;
    }

    /**
     * 读取项目根下的 CLAUDE.md 文件（若存在且大小合理）。
     *
     * 限制：最多读取 128KB，避免把超大文档塞进 system prompt 炸预算。
     */
    private static String loadProjectContext(Path cwd) {
        try {
            Path contextFile = cwd.resolve("CLAUDE.md");
            if (!Files.isRegularFile(contextFile)) {
                return "";
            }
            long size = Files.size(contextFile);
            if (size > 128L * 1024L) {
                // 超大文件只截取前 128KB
                byte[] bytes = Files.readAllBytes(contextFile);
                return new String(bytes, 0, (int) Math.min(bytes.length, 128 * 1024),
                        java.nio.charset.StandardCharsets.UTF_8)
                        + "\n...[CLAUDE.md 已截断]";
            }
            return Files.readString(contextFile, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 读不到也没关系，静默降级
            return "";
        }
    }

    /**
     * 运行单次查询模式
     *
     * 使用 try-with-resources 确保 QueryEngine（及其持有的 ApiClient）在完成后被正确关闭。
     * 结束时额外打印一次成本摘要，便于 CI/脚本化场景做预算核算。
     */
    private int runPrintMode(Settings settings) {
        try (QueryEngine queryEngine = buildQueryEngine(settings)) {
            // P-01：-c / -r 在单次查询模式下同样生效，让会话能接着之前的上下文跑
            tryRestoreSession(queryEngine);
            System.out.println("正在处理...\n");
            queryEngine.submitMessage(printPrompt, getEventConsumerForFormat()).join();
            System.out.println();
            // P-01：支持 --output-format=json 在结尾输出结构化结果，便于 CI / 脚本消费
            if ("json".equalsIgnoreCase(outputFormat)) {
                emitFinalJson(queryEngine);
            }
            printCostSummary(queryEngine);
            return 0;
        } catch (Exception e) {
            return reportFatal("单次查询失败", e);
        }
    }

    /**
     * 根据 --output-format 返回对应的事件消费者。
     * <ul>
     *   <li>{@code text}（默认）：走原有的彩色控制台渲染；</li>
     *   <li>{@code stream-json}：按行输出 JSON 事件，便于上游按行解析；</li>
     *   <li>{@code json}：在过程中静默，仅在结束后输出一次完整结果 JSON。</li>
     * </ul>
     */
    private java.util.function.Consumer<StreamEvent> getEventConsumerForFormat() {
        if (outputFormat == null) {
            return this::handleStreamEventConsole;
        }
        return switch (outputFormat.toLowerCase()) {
            case "stream-json" -> this::handleStreamEventJsonLine;
            case "json" -> event -> { /* 静默，由 emitFinalJson 统一输出 */ };
            default -> this::handleStreamEventConsole;
        };
    }

    /**
     * 把单个流式事件以 JSON Line 形式输出到 stdout。
     */
    private void handleStreamEventJsonLine(StreamEvent event) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", event.getClass().getSimpleName());
            if (event instanceof AssistantTextDelta d) {
                payload.put("text", d.getText());
            } else if (event instanceof ToolExecutionStarted s) {
                payload.put("tool", s.getToolName());
            } else if (event instanceof ToolExecutionCompleted c) {
                payload.put("tool", c.getToolName());
                payload.put("error", c.isError());
                payload.put("result", c.getResult());
            } else if (event instanceof AssistantTurnComplete t) {
                payload.put("message", t.getMessage());
            } else if (event instanceof UsageReport r) {
                payload.put("report", r.toString());
            }
            System.out.println(
                    io.leavesfly.jharness.util.JacksonUtils.MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            logger.debug("stream-json 序列化失败（忽略）", e);
        }
    }

    /**
     * 在 {@code --output-format=json} 模式下，查询结束后输出一份结构化摘要 JSON。
     */
    private void emitFinalJson(QueryEngine queryEngine) {
        try {
            var tracker = queryEngine.getCostTracker();
            // 取最后一条 assistant 消息的文本作为 output（尽力而为）
            String answer = "";
            for (var m : queryEngine.getMessages()) {
                if (m.getRole() == io.leavesfly.jharness.core.engine.model.MessageRole.ASSISTANT) {
                    StringBuilder sb = new StringBuilder();
                    for (var b : m.getContent()) {
                        if (b instanceof io.leavesfly.jharness.core.engine.model.TextBlock t) {
                            sb.append(t.getText());
                        }
                    }
                    answer = sb.toString();
                }
            }
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", "final");
            payload.put("output", answer);
            payload.put("requests", tracker.getRequestCount());
            payload.put("input_tokens", tracker.getTotalInputTokens());
            payload.put("output_tokens", tracker.getTotalOutputTokens());
            payload.put("session_cost_usd", tracker.getSessionCostUsd().doubleValue());
            System.out.println(
                    io.leavesfly.jharness.util.JacksonUtils.MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            logger.debug("final JSON 序列化失败（忽略）", e);
        }
    }

    /**
     * 运行交互式模式
     *
     * 使用 try-with-resources 保证 QueryEngine（及其持有的 ApiClient/HTTP 连接池/线程池）
     * 在会话结束或异常退出时被正确释放，避免 JVM 关闭时线程残留。
     */
    private int runInteractiveMode(Settings settings) {
        try (QueryEngine queryEngine = buildQueryEngine(settings)) {
            // P-01：交互式模式也支持 -c / -r，启动时自动恢复上次会话或指定会话
            tryRestoreSession(queryEngine);
            if (enableTUI) {
                logger.info("启动 Lanterna TUI 界面");
                TerminalUI tui = new TerminalUI();
                tui.setQueryEngine(queryEngine);
                // 【B-10】TUI 之前只挂了基础 CommandRegistry()，导致 /mcp /session /cron /agents
                // 等增强命令不可用；这里改用 createFullRegistry，让 TUI 与控制台拥有一致的命令面板。
                tui.setCommandRegistry(buildFullCommandRegistry(queryEngine, settings));
                tui.setStatus(settings.getModel(), permissionMode, "就绪");
                tui.start();
            } else {
                ConsoleInteractiveSession session = new ConsoleInteractiveSession(
                        queryEngine, settings.getModel(), permissionMode);
                session.start();
            }
            // 会话正常结束时展示一次成本摘要（异常退出走 catch，不展示）
            printCostSummary(queryEngine);
            return 0;
        } catch (Exception e) {
            return reportFatal("交互式会话异常退出", e);
        }
    }

    /**
     * 【新增】尽力构建"完整命令注册表"，失败时降级为基础版。
     *
     * <p>由于 {@link CommandRegistry#createFullRegistry} 依赖 TeamRegistry/AgentOrchestrator 等
     * 对象，而这些对象目前在 {@link #buildQueryEngine(Settings)} 内部按需创建，这里复用 engine 已
     * 暴露的 ToolRegistry 作为桥梁，其余依赖通过轻量新实例提供（不连接 MCP/Cron，仅用于命令展示）。
     */
    private CommandRegistry buildFullCommandRegistry(QueryEngine engine, Settings settings) {
        try {
            io.leavesfly.jharness.agent.coordinator.TeamRegistry teamRegistry =
                    new io.leavesfly.jharness.agent.coordinator.TeamRegistry();
            io.leavesfly.jharness.agent.coordinator.AgentOrchestrator orchestrator =
                    new io.leavesfly.jharness.agent.coordinator.AgentOrchestrator(engine, engine.getToolRegistry());
            McpClientManager mcpForUi = new McpClientManager();
            CronRegistry cronForUi = new CronRegistry(Settings.getDefaultDataDir());
            HookRegistry hookForUi = new HookRegistry();
            CommandRegistry registry = CommandRegistry.createFullRegistry(
                    settings,
                    engine,
                    engine.getToolRegistry(),
                    mcpForUi,
                    teamRegistry,
                    orchestrator,
                    cronForUi,
                    hookForUi);

            // 【P0-wire-up】注册插件提供的 slash commands 和 subagent
            List<LoadedPlugin> plugins = extractLoadedPlugins(engine);
            registerPluginCommands(registry, plugins);
            registerPluginAgents(teamRegistry, plugins);

            return registry;
        } catch (Exception e) {
            logger.warn("构建完整命令注册表失败，降级为基础版", e);
            return new CommandRegistry();
        }
    }

    /**
     * 【P0-wire-up】从 engine 的 metadata 提取已加载插件列表（安全过滤 Object 类型）。
     */
    @SuppressWarnings("unchecked")
    private static List<LoadedPlugin> extractLoadedPlugins(QueryEngine engine) {
        if (engine == null) return List.of();
        List<Object> raw = engine.getLoadedPlugins();
        if (raw == null || raw.isEmpty()) return List.of();
        List<LoadedPlugin> list = new java.util.ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof LoadedPlugin lp) list.add(lp);
        }
        return list;
    }

    /**
     * 统一 fatal 错误呈现：
     * <ul>
     *   <li>堆栈走 logger.error，配合日志配置可投递到文件 / 监控；</li>
     *   <li>stderr 只输出 "前缀: 原因"，让脚本调用方解析更容易；</li>
     *   <li>debug 模式下额外打印根因，帮助定位配置问题。</li>
     * </ul>
     */
    private int reportFatal(String prefix, Throwable e) {
        logger.error(prefix, e);
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String reason = (root.getMessage() == null || root.getMessage().isBlank())
                ? root.getClass().getSimpleName()
                : root.getMessage();
        System.err.println(prefix + ": " + reason);
        if (debug && root != e) {
            System.err.println("  根因类型: " + root.getClass().getName());
        }
        return 1;
    }

    /**
     * 打印本次会话的成本摘要。不抛异常，不影响主流程。
     */
    private void printCostSummary(QueryEngine queryEngine) {
        try {
            var tracker = queryEngine.getCostTracker();
            if (tracker.getRequestCount() == 0) {
                return;
            }
            System.out.printf(
                    "📊 用量摘要: 请求=%d, 输入=%d tok, 输出=%d tok, 会话=$%.4f, 今日=$%.4f%n",
                    tracker.getRequestCount(),
                    tracker.getTotalInputTokens(),
                    tracker.getTotalOutputTokens(),
                    tracker.getSessionCostUsd().doubleValue(),
                    tracker.getDailyCostUsd().doubleValue());
        } catch (Exception e) {
            logger.debug("打印成本摘要失败（忽略）", e);
        }
    }

    /**
     * 控制台模式下处理流式事件。
     *
     * 说明：
     * <ul>
     *   <li>UsageReport 走 debug 日志，默认不打扰控制台输出，debug 模式下会显示；</li>
     *   <li>AssistantTurnComplete 若携带提示文本（如"达到最大轮次限制"），需完整输出以便排障。</li>
     * </ul>
     */
    private void handleStreamEventConsole(StreamEvent event) {
        if (event instanceof AssistantTextDelta textDelta) {
            System.out.print(textDelta.getText());
            System.out.flush();
        } else if (event instanceof ToolExecutionStarted toolStart) {
            System.out.println("\n🔧 执行工具: " + toolStart.getToolName());
        } else if (event instanceof ToolExecutionCompleted toolDone) {
            String prefix = toolDone.isError() ? "❌ 工具失败: " : "✅ 工具完成: ";
            System.out.println(prefix + toolDone.getToolName());
        } else if (event instanceof AssistantTurnComplete turn) {
            String msg = turn.getMessage();
            if (msg != null && !msg.isBlank()) {
                System.out.println();
                System.out.println(msg);
            } else {
                System.out.println();
            }
        } else if (event instanceof UsageReport report) {
            // 控制台模式下成本信息只打 debug 日志，避免打断用户阅读模型输出
            logger.debug("usage: {}", report);
        }
    }
}
