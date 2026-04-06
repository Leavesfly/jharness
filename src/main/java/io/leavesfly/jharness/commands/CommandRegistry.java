package io.leavesfly.jharness.commands;

import io.leavesfly.jharness.commands.handlers.*;
import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.coordinator.AgentOrchestrator;
import io.leavesfly.jharness.coordinator.TeamRegistry;
import io.leavesfly.jharness.engine.QueryEngine;
import io.leavesfly.jharness.engine.stream.StreamEvent;
import io.leavesfly.jharness.hooks.HookRegistry;
import io.leavesfly.jharness.mcp.McpClientManager;
import io.leavesfly.jharness.memory.MemoryManager;
import io.leavesfly.jharness.services.CronRegistry;
import io.leavesfly.jharness.sessions.SessionStorage;
import io.leavesfly.jharness.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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

        // 注册会话命令
        Path sessionsDir = Paths.get(System.getProperty("user.home"), ".openharness", "sessions");
        SessionStorage sessionStorage = new SessionStorage(sessionsDir);

        registry.register(SessionCommandHandler.createResumeCommand(sessionStorage));
        registry.register(SessionCommandHandler.createExportCommand());
        registry.register(SessionCommandHandler.createShareCommand(sessionStorage));
        registry.register(SessionCommandHandler.createSessionCommand(sessionStorage));
        registry.register(SessionCommandHandler.createTagCommand(sessionStorage));
        registry.register(SessionCommandHandler.createRewindCommand());
        registry.register(SessionCommandHandler.createCopyCommand());
        registry.register(SessionCommandHandler.createCompactCommand());
        registry.register(SessionCommandHandler.createContextCommand());
        registry.register(SessionCommandHandler.createSummaryCommand());


        // 注册 Cron 命令
        if (cronRegistry != null) {
            registry.register(CronCommandHandler.createCronCommand(cronRegistry));
        }

        // 注册增强版 Hooks 命令
        if (hookRegistry != null) {
            registry.register(SystemCommandHandler.createHooksCommandWithRegistry(hookRegistry));
        }        logger.info("完整命令注册完成，共 {} 个命令", registry.size());
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
        register(cmd("status", "会话状态", (a, c, e) -> CompletableFuture.completedFuture(CommandResult.success("状态: 就绪"))));
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
        Path sessionsDir = Paths.get(System.getProperty("user.home"), ".openharness", "sessions");
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
        Path memoryDir = Paths.get(System.getProperty("user.home"), ".openharness", "memory");
        MemoryManager memoryManager = new MemoryManager(memoryDir);

        register(SystemCommandHandler.createMemoryCommand(memoryManager));
        register(SystemCommandHandler.createUsageCommand());
        register(SystemCommandHandler.createCostCommand());
        register(SystemCommandHandler.createStatsCommand());
        register(SystemCommandHandler.createHooksCommand());
        register(SystemCommandHandler.createVimCommand());
        register(SystemCommandHandler.createVoiceCommand());

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