package io.leavesfly.jharness;

import io.leavesfly.jharness.app.bootstrap.QueryEngineBuilder;
import io.leavesfly.jharness.app.cli.CliRunners;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.config.SettingsBootstrap;
import io.leavesfly.jharness.integration.api.OpenAiApiClient;
import io.leavesfly.jharness.kernel.engine.QueryEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * JHarness 应用程序主入口（4.8 拆分后：仅保留 CLI 选项 + 编排）。
 *
 * <p>实际工作委派给：
 * <ul>
 *   <li>{@link QueryEngineBuilder}：装配 QueryEngine（含 API/权限/工具/插件/MCP/Hook/压缩/自动保存）；</li>
 *   <li>{@link CliRunners}：单次查询模式与交互式模式入口；</li>
 *   <li>{@code app.cli.CliConsole / SessionRestorer} 与 {@code app.bootstrap.PluginBootstrap / McpBootstrap}：辅助职责。</li>
 * </ul>
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

        // 首次启动从 classpath 释放默认配置与默认插件到 ~/.jharness，幂等
        SettingsBootstrap.seedIfAbsent();

        Settings settings = Settings.load();
        applyCliOverrides(settings);
        logger.info("模型: {}", settings.getModel());

        // API Key 校验：本地端点（Ollama 等）允许为空
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

        QueryEngine engine = new QueryEngineBuilder(settings, permissionMode, resumeSessionId).build();
        if (printPrompt != null && !printPrompt.isEmpty()) {
            logger.info("单次查询模式: {}", printPrompt);
            return CliRunners.runPrintMode(engine, printPrompt, outputFormat,
                    resumeSessionId, continueSession, debug);
        }
        logger.info("交互式模式");
        return CliRunners.runInteractiveMode(engine, settings, permissionMode, enableTUI,
                resumeSessionId, continueSession, debug);
    }

    private void applyCliOverrides(Settings settings) {
        if (model != null && !model.isBlank()) {
            settings.setModel(model);
        }
        settings.setMaxTurns(maxTurns);
        settings.setPermissionMode(permissionMode);
    }
}
