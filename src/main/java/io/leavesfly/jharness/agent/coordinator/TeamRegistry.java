package io.leavesfly.jharness.agent.coordinator;

import io.leavesfly.jharness.core.engine.stream.StreamEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 团队注册表
 *
 * 管理多智能体团队的创建、删除、消息传递和任务执行。
 */
public class TeamRegistry {
    private final Map<String, TeamRecord> teams = new ConcurrentHashMap<>();
    private final AgentOrchestrator orchestrator;
    
    public TeamRegistry(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }
    
    public TeamRegistry() {
        this.orchestrator = null;
    }

    /**
     * 创建团队
     *
     * 使用完整 UUID 作为团队 ID，避免截断后碰撞风险。
     */
    public TeamRecord createTeam(String name, String description) {
        String id = UUID.randomUUID().toString();
        TeamRecord team = new TeamRecord(id, name, description);
        teams.put(name, team);
        return team;
    }
    
    /**
     * 创建带 Agent 的团队
     */
    public TeamRecord createTeamWithAgents(String name, String description, List<String> agentTaskIds) {
        TeamRecord team = createTeam(name, description);
        for (String taskId : agentTaskIds) {
            team.addAgent(taskId);
        }
        return team;
    }

    /**
     * 删除团队
     */
    public boolean deleteTeam(String name) {
        TeamRecord team = teams.get(name);
        if (team != null) {
            team.setStatus("deleted");
            teams.remove(name);
            return true;
        }
        return false;
    }

    /**
     * 添加智能体到团队
     */
    public void addAgent(String teamName, String taskId) {
        addAgent(teamName, taskId, "worker");
    }
    
    /**
     * 添加智能体到团队（带角色）
     */
    public void addAgent(String teamName, String taskId, String role) {
        TeamRecord team = teams.get(teamName);
        if (team != null) {
            team.addAgent(taskId, role);
        }
    }
    
    /**
     * 从团队中移除智能体
     *
     * 注意：必须调用 TeamRecord.removeAgent() 操作内部集合，
     * 而非通过 getAgents() 获取副本后操作（副本修改不影响原始数据）。
     */
    public boolean removeAgent(String teamName, String taskId) {
        TeamRecord team = teams.get(teamName);
        if (team != null) {
            return team.removeAgent(taskId);
        }
        return false;
    }

    /**
     * 向团队发送消息
     */
    public void sendMessage(String teamName, String message) {
        sendMessage(teamName, message, "system");
    }
    
    /**
     * 向团队发送消息（带发送者）
     */
    public void sendMessage(String teamName, String message, String sender) {
        TeamRecord team = teams.get(teamName);
        if (team != null) {
            team.addMessage(message, sender);
        }
    }
    
    /**
     * 在团队中执行任务
     */
    public CompletableFuture<AgentOrchestrator.AgentResult> executeTask(
            String teamName, 
            String taskName, 
            String prompt,
            Consumer<StreamEvent> eventConsumer) {
        
        TeamRecord team = teams.get(teamName);
        if (team == null) {
            return CompletableFuture.completedFuture(
                new AgentOrchestrator.AgentResult(null, taskName, false, null, "团队不存在: " + teamName));
        }
        
        AgentOrchestrator.AgentTask task = new AgentOrchestrator.AgentTask(taskName, prompt);
        team.setMetadata("last_task", taskName);
        team.setMetadata("last_task_time", Instant.now().toString());
        
        // 通过编排器执行任务
        if (orchestrator != null) {
            return orchestrator.executeParallel(
                List.of(task), 
                eventConsumer != null ? eventConsumer : (e -> {})
            ).thenApply(results -> results.get(0));
        }
        
        return CompletableFuture.completedFuture(
            new AgentOrchestrator.AgentResult(null, taskName, false, null, "编排器未配置"));
    }
    
    /**
     * 在团队中并行执行多个任务
     */
    public CompletableFuture<List<AgentOrchestrator.AgentResult>> executeParallelTasks(
            String teamName,
            List<AgentOrchestrator.AgentTask> tasks,
            Consumer<StreamEvent> eventConsumer) {
        
        TeamRecord team = teams.get(teamName);
        if (team == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        if (orchestrator != null) {
            return orchestrator.executeParallel(tasks, eventConsumer != null ? eventConsumer : (e -> {}));
        }
        
        return CompletableFuture.completedFuture(List.of());
    }
    
    /**
     * 在团队中顺序执行多个任务
     */
    public CompletableFuture<List<AgentOrchestrator.AgentResult>> executeSequentialTasks(
            String teamName,
            List<AgentOrchestrator.AgentTask> tasks,
            Consumer<StreamEvent> eventConsumer) {
        
        TeamRecord team = teams.get(teamName);
        if (team == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        if (orchestrator != null) {
            return orchestrator.executeSequential(tasks, eventConsumer != null ? eventConsumer : (e -> {}));
        }
        
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * 获取团队
     */
    public TeamRecord getTeam(String name) {
        return teams.get(name);
    }

    /**
     * 列出所有团队
     */
    public List<TeamRecord> listTeams() {
        return new ArrayList<>(teams.values());
    }
    
    /**
     * 获取团队统计信息
     */
    public Map<String, Object> getTeamStats(String teamName) {
        TeamRecord team = teams.get(teamName);
        if (team == null) {
            return Map.of("error", "团队不存在");
        }
        
        return Map.of(
            "name", team.getName(),
            "id", team.getId(),
            "status", team.getStatus(),
            "agentCount", team.getAgents().size(),
            "messageCount", team.getMessages().size(),
            "createdAt", team.getCreatedAt().toString()
        );
    }
}
