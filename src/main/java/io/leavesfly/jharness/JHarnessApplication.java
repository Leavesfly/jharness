package io.leavesfly.jharness;

import io.leavesfly.jharness.integration.api.OpenAiApiClient;
import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.agent.coordinator.TeamRegistry;
import io.leavesfly.jharness.core.engine.QueryEngine;
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

import java.nio.file.Path;
import java.nio.file.Paths;
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

        // 加载配置
        Settings settings = Settings.load();
        applyCliOverrides(settings);

        logger.info("模型: {}", settings.getModel());

        // 校验 API Key
        if (settings.getApiKey() == null || settings.getApiKey().isBlank()) {
            logger.error("未配置 API Key，请设置环境变量 ANTHROPIC_API_KEY 或在 ~/.jharness/settings.json 中配置");
            System.err.println("错误: 未配置 API Key。请设置环境变量 ANTHROPIC_API_KEY 或在 ~/.jharness/settings.json 中配置。");
            return 1;
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
     * 构建 QueryEngine 实例（组装完整依赖链）
     */
    private QueryEngine buildQueryEngine(Settings settings) {
        Path cwd = Paths.get(System.getProperty("user.dir"));

        OpenAiApiClient apiClient = new OpenAiApiClient(
                settings.getBaseUrl(),
                settings.getApiKey(),
                settings.getModel(),
                settings.getMaxTokens());

        BackgroundTaskManager taskManager = new BackgroundTaskManager(
                Settings.getDefaultDataDir().resolve("tasks"));
        TeamRegistry teamRegistry = new TeamRegistry();
        SkillRegistry skillRegistry = SkillRegistry.withProject(cwd);
        McpClientManager mcpManager = new McpClientManager();
        CronRegistry cronRegistry = new CronRegistry(Settings.getDefaultDataDir());

        ToolRegistry toolRegistry = ToolRegistry.withDefaults(
                settings, taskManager, teamRegistry, skillRegistry, mcpManager, cronRegistry);

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

        String systemPrompt = new SystemPromptBuilder(
                "你是 JHarness，一个强大的 AI 编程助手。你可以使用工具来帮助用户完成各种任务。")
                .build();

        QueryEngine engine = new QueryEngine(apiClient, toolRegistry, permissionChecker,
                cwd, systemPrompt, settings.getMaxTurns());

        // F-P0-5：把当前模型名注入 CostTracker，让价格表查询生效
        engine.getCostTracker().setModelName(settings.getModel());

        // F-P1-1：为 AgentTool 注入共享资源，启用 in_process 模式
        BaseTool<?> agentRaw = toolRegistry.get("agent_spawn");
        if (agentRaw instanceof AgentTool agentTool) {
            agentTool.configureInProcess(apiClient, toolRegistry, permissionChecker);
            logger.info("AgentTool in_process 模式已配置");
        }

        return engine;
    }

    /**
     * 运行单次查询模式
     *
     * 使用 try-with-resources 确保 QueryEngine（及其持有的 ApiClient）在完成后被正确关闭。
     * 结束时额外打印一次成本摘要，便于 CI/脚本化场景做预算核算。
     */
    private int runPrintMode(Settings settings) {
        try (QueryEngine queryEngine = buildQueryEngine(settings)) {
            System.out.println("正在处理...\n");
            queryEngine.submitMessage(printPrompt, this::handleStreamEventConsole).join();
            System.out.println();
            printCostSummary(queryEngine);
            return 0;
        } catch (Exception e) {
            return reportFatal("单次查询失败", e);
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
            if (enableTUI) {
                logger.info("启动 Lanterna TUI 界面");
                TerminalUI tui = new TerminalUI();
                tui.setQueryEngine(queryEngine);
                tui.setCommandRegistry(new CommandRegistry());
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
