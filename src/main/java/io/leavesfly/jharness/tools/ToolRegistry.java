package io.leavesfly.jharness.tools;

import io.leavesfly.jharness.config.Settings;
import io.leavesfly.jharness.coordinator.TeamRegistry;
import io.leavesfly.jharness.mcp.McpClientManager;
import io.leavesfly.jharness.services.CronRegistry;
import io.leavesfly.jharness.skills.SkillRegistry;
import io.leavesfly.jharness.tasks.BackgroundTaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表
 *
 * 管理所有已注册的工具，提供工具查询和 API Schema 生成。
 * 使用 ConcurrentHashMap 保证线程安全。
 */
public class ToolRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, BaseTool<?>> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具
     *
     * @param tool 工具实例
     */
    public void register(BaseTool<?> tool) {
        tools.put(tool.getName(), tool);
        logger.debug("已注册工具: {}", tool.getName());
    }

    /**
     * 获取工具
     *
     * @param name 工具名称
     * @return 工具实例，如果不存在返回 null
     */
    public BaseTool<?> get(String name) {
        return tools.get(name);
    }

    /**
     * 检查工具是否存在
     *
     * @param name 工具名称
     * @return 如果工具存在返回 true
     */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /**
     * 获取所有工具名称
     *
     * @return 工具名称列表
     */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * 获取所有工具
     *
     * @return 工具列表
     */
    public Collection<BaseTool<?>> getAllTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * 生成所有工具的 API Schema
     *
     * @return API Schema 列表
     */
    public List<Map<String, Object>> toApiSchema() {
        List<Map<String, Object>> schemas = new ArrayList<>();
        // 创建快照，避免并发修改异常
        List<BaseTool<?>> toolsSnapshot = new ArrayList<>(tools.values());
        for (BaseTool<?> tool : toolsSnapshot) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("name", tool.getName());
            schema.put("description", tool.getDescription());
            schema.put("input_schema", tool.toApiSchema());
            schemas.add(schema);
        }
        return schemas;
    }

    /**
     * 注册默认工具集
     *
     * @param settings 配置实例
     * @param taskManager 任务管理器
     * @param teamRegistry 团队管理器
     * @param skillRegistry 技能管理器
     * @param mcpManager MCP 客户端管理器
     * @param cronRegistry Cron 作业注册表
     * @return 工具注册表实例
     */
    public static ToolRegistry withDefaults(Settings settings, BackgroundTaskManager taskManager,
                                            TeamRegistry teamRegistry, SkillRegistry skillRegistry,
                                            McpClientManager mcpManager, CronRegistry cronRegistry) {
        ToolRegistry registry = new ToolRegistry();

        // 核心文件工具
        registry.register(new FileReadTool());
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());

        // 代码智能工具
        registry.register(new LspTool());

        // Notebook 工具
        registry.register(new NotebookEditTool());

        // 系统工具
        registry.register(new BashTool());
        registry.register(new SleepTool());

        // 任务管理工具
        registry.register(new TodoWriteTool());
        registry.register(new TaskCreateTool(taskManager));
        registry.register(new TaskGetTool(taskManager));
        registry.register(new TaskListTool(taskManager));
        registry.register(new TaskStopTool(taskManager));
        registry.register(new TaskOutputTool(taskManager));
        registry.register(new TaskUpdateTool(taskManager));

        // 搜索和元工具
        registry.register(new WebFetchTool());
        registry.register(new WebSearchTool());
        registry.register(new ToolSearchTool(registry));
        registry.register(new BriefTool());
        registry.register(new ConfigTool(settings));

        // 模式切换工具
        registry.register(new EnterPlanModeTool(settings));
        registry.register(new ExitPlanModeTool(settings));
        registry.register(new EnterWorktreeTool());
        registry.register(new ExitWorktreeTool());

        // Team 管理工具
        registry.register(new TeamCreateTool(teamRegistry));
        registry.register(new TeamDeleteTool(teamRegistry));

        // Agent 工具
        registry.register(new AgentTool(taskManager));
        registry.register(new SendMessageTool(taskManager));

        // 交互工具
        registry.register(new AskUserQuestionTool());

        // 技能工具
        if (skillRegistry != null) {
            registry.register(new SkillTool(skillRegistry));
        }

        // MCP 资源工具
        if (mcpManager != null) {
            registry.register(new ListMcpResourcesTool(mcpManager));
            registry.register(new ReadMcpResourceTool(mcpManager));
            registry.register(new McpAuthTool(mcpManager, settings));

            // 注册动态 MCP 工具适配器
            for (var toolInfo : mcpManager.listTools()) {
                registry.register(new McpToolAdapter(mcpManager, toolInfo));
            }
        }

        // Cron 定时任务工具
        if (cronRegistry != null) {
            registry.register(new CronCreateTool(cronRegistry));
            registry.register(new CronListTool(cronRegistry));
            registry.register(new CronDeleteTool(cronRegistry));
            registry.register(new RemoteTriggerTool(cronRegistry));
        }

        logger.info("已注册 {} 个默认工具", registry.size());
        return registry;
    }

    public int size() {
        return tools.size();
    }
}
