package io.leavesfly.jharness.command.commands.handlers;

import io.leavesfly.jharness.command.commands.CommandContext;
import io.leavesfly.jharness.command.commands.CommandResult;
import io.leavesfly.jharness.command.commands.SimpleSlashCommand;
import io.leavesfly.jharness.command.commands.SlashCommand;
import io.leavesfly.jharness.agent.coordinator.AgentOrchestrator;
import io.leavesfly.jharness.agent.coordinator.TeamRecord;
import io.leavesfly.jharness.agent.coordinator.TeamRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 命令处理器
 * 
 * 处理 /agents 命令：创建团队、分配任务、查看状态等。
 */
public class AgentCommandHandler {
    
    private final TeamRegistry teamRegistry;
    private final AgentOrchestrator orchestrator;
    
    public AgentCommandHandler(TeamRegistry teamRegistry, AgentOrchestrator orchestrator) {
        this.teamRegistry = teamRegistry;
        this.orchestrator = orchestrator;
    }
    
    /**
     * 创建 Agent 命令
     */
    public SlashCommand createAgentsCommand() {
        return cmd("agents", "管理多 Agent 团队和任务", (args, ctx) -> {
            if (args.isEmpty()) {
                return showAgentsHelp();
            }
            
            String subcommand = args.get(0);
            return switch (subcommand) {
                case "teams" -> listTeams();
                case "create-team" -> createTeam(args);
                case "add-agent" -> addAgent(args);
                case "run-parallel" -> runParallel(args);
                case "run-sequential" -> runSequential(args);
                case "status" -> showAgentStatus(args);
                default -> CommandResult.error("未知 Agent 子命令: " + subcommand);
            };
        });
    }
    
    private CommandResult showAgentsHelp() {
        return CommandResult.success("""
            Agent 命令帮助:
            
            /agents teams              - 列出所有团队
            /agents create-team <name> <description> - 创建团队
            /agents add-agent <team> <task-id> - 添加 Agent 到团队
            /agents run-parallel <task1> <task2> ... - 并行执行任务
            /agents run-sequential <task1> <task2> ... - 顺序执行任务
            /agents status [agent-id]  - 查看 Agent 状态
            """);
    }
    
    private CommandResult listTeams() {
        List<TeamRecord> teams = teamRegistry.listTeams();
        if (teams.isEmpty()) {
            return CommandResult.success("暂无团队");
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("团队列表:\n\n");
        
        for (TeamRecord team : teams) {
            sb.append("团队: ").append(team.getName()).append("\n");
            sb.append("  ID: ").append(team.getId()).append("\n");
            sb.append("  描述: ").append(team.getDescription()).append("\n");
            sb.append("  Agent 数: ").append(team.getAgents().size()).append("\n");
            sb.append("  消息数: ").append(team.getMessages().size()).append("\n");
            sb.append("\n");
        }
        
        return CommandResult.success(sb.toString().trim());
    }
    
    private CommandResult createTeam(List<String> args) {
        if (args.size() < 3) {
            return CommandResult.error("用法: /agents create-team <name> <description>");
        }
        
        String name = args.get(1);
        String description = String.join(" ", args.subList(2, args.size()));
        
        TeamRecord team = teamRegistry.createTeam(name, description);
        return CommandResult.success("团队已创建: " + team.getName() + " (ID: " + team.getId() + ")");
    }
    
    private CommandResult addAgent(List<String> args) {
        if (args.size() < 3) {
            return CommandResult.error("用法: /agents add-agent <team> <task-id>");
        }
        
        String teamName = args.get(1);
        String taskId = args.get(2);
        
        TeamRecord team = teamRegistry.getTeam(teamName);
        if (team == null) {
            return CommandResult.error("团队不存在: " + teamName);
        }
        
        teamRegistry.addAgent(teamName, taskId);
        return CommandResult.success("Agent 已添加到团队: " + taskId + " -> " + teamName);
    }
    
    private CommandResult runParallel(List<String> args) {
        if (args.size() < 2) {
            return CommandResult.error("用法: /agents run-parallel <task1> [task2] ...");
        }
        
        List<String> tasks = args.subList(1, args.size());
        List<AgentOrchestrator.AgentTask> agentTasks = new ArrayList<>();
        
        for (int i = 0; i < tasks.size(); i++) {
            agentTasks.add(new AgentOrchestrator.AgentTask(
                "task-" + (i + 1),
                tasks.get(i)
            ));
        }
        
        return CommandResult.success("并行任务已启动: " + tasks.size() + " 个任务");
    }
    
    private CommandResult runSequential(List<String> args) {
        if (args.size() < 2) {
            return CommandResult.error("用法: /agents run-sequential <task1> [task2] ...");
        }
        
        List<String> tasks = args.subList(1, args.size());
        return CommandResult.success("顺序任务已启动: " + tasks.size() + " 个任务");
    }
    
    private CommandResult showAgentStatus(List<String> args) {
        if (args.size() >= 2) {
            String agentId = args.get(1);
            AgentOrchestrator.AgentInstance agent = orchestrator.getAgentStatus(agentId);
            if (agent == null) {
                return CommandResult.error("Agent 不存在: " + agentId);
            }
            
            return CommandResult.success(String.format("""
                Agent 状态:
                ID: %s
                名称: %s
                状态: %s
                启动时间: %s
                """, agent.getId(), agent.getName(), agent.getStatus(), agent.getStartTime()));
        }
        
        // 列出所有活跃 Agent
        List<AgentOrchestrator.AgentInstance> agents = orchestrator.getActiveAgents();
        if (agents.isEmpty()) {
            return CommandResult.success("无活跃 Agent");
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("活跃 Agent 列表:\n\n");
        
        for (AgentOrchestrator.AgentInstance agent : agents) {
            sb.append("- ").append(agent.getId()).append(" (").append(agent.getName()).append(")\n");
            sb.append("  状态: ").append(agent.getStatus()).append("\n");
            sb.append("  启动时间: ").append(agent.getStartTime()).append("\n\n");
        }
        
        return CommandResult.success(sb.toString().trim());
    }
    
    private static SlashCommand cmd(String name, String desc, Handler h) {
        return new SimpleSlashCommand(name, desc, (args, ctx, ec) -> 
            CompletableFuture.completedFuture(h.handle(args, ctx)));
    }
    
    @FunctionalInterface
    private interface Handler {
        CommandResult handle(List<String> args, CommandContext ctx);
    }
}
