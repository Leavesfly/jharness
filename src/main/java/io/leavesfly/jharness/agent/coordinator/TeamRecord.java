package io.leavesfly.jharness.agent.coordinator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 团队记录
 * 
 * 存储多 Agent 团队的配置、状态和执行历史。
 */
public class TeamRecord {
    private final String id;
    private final String name;
    private final String description;
    private final Instant createdAt;
    private final List<AgentInfo> agents = new CopyOnWriteArrayList<>();
    private final List<TeamMessage> messages = new CopyOnWriteArrayList<>();
    private final Map<String, String> metadata = new ConcurrentHashMap<>();
    private String status = "active";
    
    public TeamRecord(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    
    public List<AgentInfo> getAgents() {
        return new ArrayList<>(agents);
    }
    
    public List<TeamMessage> getMessages() {
        return new ArrayList<>(messages);
    }
    
    public Map<String, String> getMetadata() {
        return new ConcurrentHashMap<>(metadata);
    }
    
    void addAgent(String taskId) {
        agents.add(new AgentInfo(taskId, Instant.now()));
    }
    
    void addAgent(String taskId, String role) {
        agents.add(new AgentInfo(taskId, role, Instant.now()));
    }
    
    /**
     * 从团队中移除指定 taskId 的 Agent
     *
     * 直接操作内部 CopyOnWriteArrayList，而非通过 getAgents() 副本操作。
     */
    boolean removeAgent(String taskId) {
        return agents.removeIf(agent -> agent.getTaskId().equals(taskId));
    }

    void addMessage(String message) {
        messages.add(new TeamMessage(message, Instant.now()));
    }
    
    void addMessage(String message, String sender) {
        messages.add(new TeamMessage(message, sender, Instant.now()));
    }
    
    void setStatus(String status) {
        this.status = status;
    }
    
    void setMetadata(String key, String value) {
        metadata.put(key, value);
    }
    
    /**
     * Agent 信息
     */
    public static class AgentInfo {
        private final String taskId;
        private final String role;
        private final Instant addedAt;
        
        public AgentInfo(String taskId, Instant addedAt) {
            this(taskId, "worker", addedAt);
        }
        
        public AgentInfo(String taskId, String role, Instant addedAt) {
            this.taskId = taskId;
            this.role = role;
            this.addedAt = addedAt;
        }
        
        public String getTaskId() { return taskId; }
        public String getRole() { return role; }
        public Instant getAddedAt() { return addedAt; }
    }
    
    /**
     * 团队消息
     */
    public static class TeamMessage {
        private final String content;
        private final String sender;
        private final Instant timestamp;
        
        public TeamMessage(String content, Instant timestamp) {
            this(content, "system", timestamp);
        }
        
        public TeamMessage(String content, String sender, Instant timestamp) {
            this.content = content;
            this.sender = sender;
            this.timestamp = timestamp;
        }
        
        public String getContent() { return content; }
        public String getSender() { return sender; }
        public Instant getTimestamp() { return timestamp; }
    }
}
