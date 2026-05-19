package io.leavesfly.jharness.app.cli;

import io.leavesfly.jharness.app.bootstrap.PluginBootstrap;
import io.leavesfly.jharness.capability.coordination.AgentOrchestrator;
import io.leavesfly.jharness.capability.coordination.TeamRegistry;
import io.leavesfly.jharness.capability.hook.HookRegistry;
import io.leavesfly.jharness.command.CommandRegistry;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.extension.plugins.LoadedPlugin;
import io.leavesfly.jharness.integration.cron.CronRegistry;
import io.leavesfly.jharness.integration.mcp.McpClientManager;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import io.leavesfly.jharness.ui.tui.ConsoleInteractiveSession;
import io.leavesfly.jharness.ui.tui.TerminalUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次查询模式与交互式模式入口（4.8 拆分自 JHarnessApplication）。
 *
 * <p>每个 run 方法独立 try-with-resources 持有 QueryEngine，
 * 保证 ApiClient/线程池/HTTP 连接池在退出时被关闭。
 */
public final class CliRunners {

    private static final Logger logger = LoggerFactory.getLogger(CliRunners.class);

    private CliRunners() {}

    /** 单次查询模式：-p/--print。 */
    public static int runPrintMode(QueryEngine queryEngine, String printPrompt,
                                   String outputFormat, String resumeSessionId,
                                   boolean continueSession, boolean debug) {
        try (queryEngine) {
            SessionRestorer.tryRestore(queryEngine, resumeSessionId, continueSession);
            System.out.println("正在处理...\n");
            queryEngine.submitMessage(printPrompt, CliConsole.eventConsumerFor(outputFormat)).join();
            System.out.println();
            if ("json".equalsIgnoreCase(outputFormat)) {
                CliConsole.emitFinalJson(queryEngine);
            }
            CliConsole.printCostSummary(queryEngine);
            return 0;
        } catch (Exception e) {
            return CliConsole.reportFatal("单次查询失败", e, debug);
        }
    }

    /** 交互式模式：TUI 或控制台。 */
    public static int runInteractiveMode(QueryEngine queryEngine, Settings settings,
                                         String permissionMode, boolean enableTUI,
                                         String resumeSessionId, boolean continueSession,
                                         boolean debug) {
        try (queryEngine) {
            SessionRestorer.tryRestore(queryEngine, resumeSessionId, continueSession);
            if (enableTUI) {
                logger.info("启动 Lanterna TUI 界面");
                TerminalUI tui = new TerminalUI();
                tui.setQueryEngine(queryEngine);
                tui.setCommandRegistry(buildFullCommandRegistry(queryEngine, settings));
                tui.setStatus(settings.getModel(), permissionMode, "就绪");
                tui.start();
            } else {
                ConsoleInteractiveSession session = new ConsoleInteractiveSession(
                        queryEngine, settings.getModel(), permissionMode);
                session.start();
            }
            CliConsole.printCostSummary(queryEngine);
            return 0;
        } catch (Exception e) {
            return CliConsole.reportFatal("交互式会话异常退出", e, debug);
        }
    }

    /**
     * 尽力构建“完整命令注册表”，失败时降级为基础版。
     */
    private static CommandRegistry buildFullCommandRegistry(QueryEngine engine, Settings settings) {
        try {
            TeamRegistry teamRegistry = new TeamRegistry();
            AgentOrchestrator orchestrator = new AgentOrchestrator(engine, engine.getToolRegistry());
            McpClientManager mcpForUi = new McpClientManager();
            CronRegistry cronForUi = new CronRegistry(Settings.getDefaultDataDir());
            HookRegistry hookForUi = new HookRegistry();
            CommandRegistry registry = CommandRegistry.createFullRegistry(
                    settings, engine, engine.getToolRegistry(),
                    mcpForUi, teamRegistry, orchestrator, cronForUi, hookForUi);

            List<LoadedPlugin> plugins = extractLoadedPlugins(engine);
            PluginBootstrap.registerPluginCommands(registry, plugins);
            PluginBootstrap.registerPluginAgents(teamRegistry, plugins);

            return registry;
        } catch (Exception e) {
            logger.warn("构建完整命令注册表失败，降级为基础版", e);
            return new CommandRegistry();
        }
    }

    /** 从 engine 中提取 LoadedPlugin 列表（安全过滤 Object 类型）。 */
    private static List<LoadedPlugin> extractLoadedPlugins(QueryEngine engine) {
        if (engine == null) return List.of();
        List<Object> raw = engine.getLoadedPlugins();
        if (raw == null || raw.isEmpty()) return List.of();
        List<LoadedPlugin> list = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof LoadedPlugin lp) list.add(lp);
        }
        return list;
    }
}
