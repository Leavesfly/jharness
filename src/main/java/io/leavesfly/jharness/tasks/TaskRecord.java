package io.leavesfly.jharness.tasks;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务记录
 */
public class TaskRecord {
    public enum TaskType {
        LOCAL_BASH,       // 本地 shell 命令
        LOCAL_AGENT,      // 本地子进程 Agent
        REMOTE_AGENT,     // 远程 Agent
        IN_PROCESS        // 进程内执行
    }

    private final String id;
    private TaskType type;
    private final String command;
    private String description;
    private String prompt;  // Agent 任务的提示词
    private final Path cwd;
    private TaskStatus status;
    private final Map<String, String> metadata = new ConcurrentHashMap<>();
    private Instant startedAt;
    private Instant endedAt;
    private Integer exitCode;
    private String model;  // Agent 使用的模型

    public TaskRecord(String id, String command, String description, Path cwd, TaskStatus status) {
        this(id, command, description, cwd, status, TaskType.LOCAL_BASH);
    }

    public TaskRecord(String id, String command, String description, Path cwd, TaskStatus status, TaskType type) {
        this.id = id;
        this.type = type;
        this.command = command;
        this.description = description;
        this.cwd = cwd;
        this.status = status;
        this.startedAt = Instant.now();
    }

    public String getId() { return id; }
    public TaskType getType() { return type; }
    public void setType(TaskType type) { this.type = type; }
    public String getCommand() { return command; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public Path getCwd() { return cwd; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) {
        this.status = status;
        if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED ||
            status == TaskStatus.STOPPED || status == TaskStatus.KILLED) {
            this.endedAt = Instant.now();
        }
    }

    public Map<String, String> getMetadata() { return new ConcurrentHashMap<>(metadata); }
    public void addMetadata(String key, String value) { metadata.put(key, value); }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public boolean isAgentTask() {
        return type == TaskType.LOCAL_AGENT || type == TaskType.REMOTE_AGENT;
    }
}
