package io.leavesfly.jharness.command.commands;

import io.leavesfly.jharness.command.commands.handlers.*;

import io.leavesfly.jharness.core.Settings;
import io.leavesfly.jharness.agent.coordinator.AgentOrchestrator;
import io.leavesfly.jharness.agent.coordinator.TeamRegistry;
import io.leavesfly.jharness.core.engine.QueryEngine;
import io.leavesfly.jharness.agent.hooks.HookRegistry;
import io.leavesfly.jharness.integration.mcp.McpClientManager;
import io.leavesfly.jharness.core.MemoryManager;
import io.leavesfly.jharness.integration.CronRegistry;
import io.leavesfly.jharness.session.sessions.SessionStorage;
import io.leavesfly.jharness.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class CommandRegistry {
    private static final Logger logger = LoggerFactory.getLogger(CommandRegistry.class);
    private final Map<String, SlashCommand> commands = new HashMap<>();

    public CommandRegistry() {
        registerAllCommands();
    }
    
    /**
     * 创建包含所有依赖的命令注册表
     */
    public static CommandRegistry createFullRegistry(
            Settings settings,
            QueryEngine queryEngine,
            ToolRegistry toolRegistry,
            McpClientManager mcpManager,
            TeamRegistry teamRegistry,
            AgentOrchestrator orchestrator,
            CronRegistry cronRegistry,
            HookRegistry hookRegistry) {

        CommandRegistry registry = new CommandRegistry();

        // 注册 MCP 命令
        McpCommandHandler mcpHandler = new McpCommandHandler(mcpManager, settings);
        registry.register(mcpHandler.createMcpCommand());

        // 注册 Agent 命令
        AgentCommandHandler agentHandler = new AgentCommandHandler(teamRegistry, orchestrator);
        registry.register(agentHandler.createAgentsCommand());

        // 【B-12】会话类命令在基础构造 registerAllCommands() 中已经注册过一次，
        // 这里如果再注册一次 createSessionCommand/createShareCommand 等，会用新的 SessionStorage
        // 实例覆盖掉旧的（`register()` 是 put 语义）。虽然功能上等价，但容易在单测中掩盖问题。
        // 改为：只在基础版未注册过的命令才补注册，保证幂等且不会"静默覆盖"。
        // 注意：基础版使用的 SessionStorage 指向同一个 ~/.jharness/sessions，语义一致。

        // 注册 Cron 命令
        if (cronRegistry != null) {
            if (!registry.hasCommand("cron")) {
                registry.register(CronCommandHandler.createCronCommand(cronRegistry));
            }
        }

        // 注册增强版 Hooks 命令（覆盖基础版的简易 /hooks，因为 Registry 版本提供持久化/触发等增强能力）
        if (hookRegistry != null) {
            registry.register(SystemCommandHandler.createHooksCommandWithRegistry(hookRegistry));
        }
        logger.info("完整命令注册完成，共 {} 个命令", registry.size());
        return registry;
    }

    private static SlashCommand cmd(String name, String desc, SimpleSlashCommand.CommandHandler h) {
        return new SimpleSlashCommand(name, desc, h);
    }

    private void registerAllCommands() {
        // 基础命令
        register(cmd("help", "显示可用命令", (args, ctx, ec) -> {
            StringBuilder sb = new StringBuilder("可用命令:\n");
            commands.values().stream()
                .sorted(Comparator.comparing(SlashCommand::getName))
                .forEach(c -> sb.append("  /").append(c.getName()).append(" - ").append(c.getDescription()).append("\n"));
            return CompletableFuture.completedFuture(CommandResult.success(sb.toString().trim()));
        }));
        register(cmd("exit", "退出程序", (a, c, e) -> CompletableFuture.completedFuture(CommandResult.success("exit"))));
        register(cmd("clear", "清空历史", (a, c, e) -> CompletableFuture.completedFuture(CommandResult.success("clear"))));
        register(cmd("status", "会话状态", (args, ctx, ec) -> {
            io.leavesfly.jharness.core.engine.QueryEngine engine = ctx.getEngine();
            io.leavesfly.jharness.core.Settings settings = ctx.getSettings();
            int msgCount = engine != null ? engine.getMessages().size() : 0;
            String usage;
            if (engine != null && engine.getCostTracker() != null) {
                usage = "input=" + engine.getCostTracker().getTotalInputTokens()
                        + " output=" + engine.getCostTracker().getTotalOutputTokens();
            } else {
                usage = "N/A";
            }
            String effort = settings != null ? settings.getEffort() : "medium";
            int passes = settings != null ? settings.getPasses() : 1;
            return java.util.concurrent.CompletableFuture.completedFuture(
                    CommandResult.success(
                            "Messages: " + msgCount + "\n"
                            + "Usage: " + usage + "\n"
                            + "Effort: " + effort + "\n"
                            + "Passes: " + passes));
        }));
        register(cmd("version", "版本", (a, c, e) -> CompletableFuture.completedFuture(CommandResult.success("JHarness 0.1.0"))));
        
        // 配置命令
        register(ConfigCommandHandler.createConfigCommand());
        register(ConfigCommandHandler.createModelCommand());
        register(ConfigCommandHandler.createPermissionsCommand());
        register(ConfigCommandHandler.createPlanCommand());
        register(ConfigCommandHandler.createSkillsCommand());
        register(ConfigCommandHandler.createTasksCommand());
        register(ConfigCommandHandler.createMcpCommand());
        register(ConfigCommandHandler.createLoginCommand());
        register(ConfigCommandHandler.createLogoutCommand());
        register(ConfigCommandHandler.createThemeCommand());
        register(ConfigCommandHandler.createDoctorCommand());
        
        // Git 命令
        register(GitCommandHandler.createDiffCommand());
        register(GitCommandHandler.createBranchCommand());
        register(GitCommandHandler.createCommitCommand());
        register(GitCommandHandler.createFilesCommand());
        
        // 会话命令
        // P-02 修复：从 ~/.openharness/sessions 改为 ~/.jharness/sessions，与项目数据目录一致
        Path sessionsDir = Settings.getDefaultDataDir().resolve("sessions");
        SessionStorage sessionStorage = new SessionStorage(sessionsDir);

        register(SessionCommandHandler.createResumeCommand(sessionStorage));
        register(SessionCommandHandler.createExportCommand());
        register(SessionCommandHandler.createShareCommand(sessionStorage));
        register(SessionCommandHandler.createSessionCommand(sessionStorage));
        register(SessionCommandHandler.createTagCommand(sessionStorage));
        register(SessionCommandHandler.createRewindCommand());
        register(SessionCommandHandler.createCopyCommand());
        register(SessionCommandHandler.createCompactCommand());
        register(SessionCommandHandler.createContextCommand());
        register(SessionCommandHandler.createSummaryCommand());
        
        // 工具和系统命令
        // P-02 修复：从 ~/.openharness/memory 改为 ~/.jharness/memories，与项目数据目录一致
        Path memoryDir = Settings.getDefaultDataDir().resolve("memories");
        MemoryManager memoryManager = new MemoryManager(memoryDir);

        register(SystemCommandHandler.createMemoryCommand(memoryManager));
        register(SystemCommandHandler.createUsageCommand());
        register(SystemCommandHandler.createCostCommand());
        register(SystemCommandHandler.createStatsCommand());
        register(SystemCommandHandler.createHooksCommand());
        register(SystemCommandHandler.createVimCommand());
        register(SystemCommandHandler.createVoiceCommand());
        register(SystemCommandHandler.createOutputStyleCommand());

        // 扩展配置命令
        register(ConfigPlusCommandHandler.createEffortCommand());
        register(ConfigPlusCommandHandler.createPassesCommand());
        register(ConfigPlusCommandHandler.createFastCommand());
        register(ConfigPlusCommandHandler.createPluginCommand());
        register(ConfigPlusCommandHandler.createReloadPluginsCommand());
        register(ConfigPlusCommandHandler.createInitCommand());

        // 工作目录命令
        register(CdCommandHandler.createCdCommand());

        // 项目上下文命令
        register(IssueCommandHandler.createIssueCommand());
        register(PrCommentsCommandHandler.createPrCommentsCommand());

        // 系统和设置命令
        register(PrivacyCommandHandler.createPrivacyCommand());
        register(UpgradeCommandHandler.createUpgradeCommand());
        register(ReleaseNotesCommandHandler.createReleaseNotesCommand());
        register(RateLimitCommandHandler.createRateLimitCommand());
        register(KeybindingsCommandHandler.createKeybindingsCommand());
        register(BridgeCommandHandler.createBridgeCommand());
        
        register(cmd("feedback", "保存反馈", (a, c, e) -> CompletableFuture.completedFuture(CommandResult.success("反馈已保存"))));
        register(cmd("onboarding", "快速入门", (a, c, e) -> CompletableFuture.completedFuture(CommandResult.success("JHarness 快速入门:\n1. 描述任务\n2. /help 查看命令\n3. /doctor 诊断\n4. /login 存储 Key"))));
        
        logger.info("已注册 {} 个命令", commands.size());
    }

    public void register(SlashCommand command) { commands.put(command.getName().toLowerCase(), command); }

    /**
     * 【B-12】判断指定名字的命令是否已被注册，用于 createFullRegistry 防重复注册。
     */
    public boolean hasCommand(String name) {
        if (name == null) return false;
        String n = name.startsWith("/") ? name.substring(1) : name;
        return commands.containsKey(n.toLowerCase());
    }

    public Optional<SlashCommand> lookup(String input) {
        String name = input.startsWith("/") ? input.substring(1) : input;
        int i = name.indexOf(" ");
        if (i > 0) name = name.substring(0, i);
        return Optional.ofNullable(commands.get(name.toLowerCase()));
    }
    public Collection<SlashCommand> getAllCommands() { return Collections.unmodifiableCollection(commands.values()); }
    public Set<String> getCommandNames() { return Collections.unmodifiableSet(commands.keySet()); }
    public int size() { return commands.size(); }
}